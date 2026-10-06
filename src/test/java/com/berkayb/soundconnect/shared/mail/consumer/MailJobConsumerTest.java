package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailRetryPublisher;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Headers;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailJobConsumerTest {
	
	@Mock private MailSenderClient mailSenderClient;
	@Mock private MailJobHelper helper;
	@Mock private MailRetryPublisher retryPublisher;
	@Mock private Channel channel;
	
	private MailJobConsumer consumer;
	
	@BeforeEach
	void setUp() {
		consumer = new MailJobConsumer(mailSenderClient, helper, retryPublisher,
				mock(com.berkayb.soundconnect.modules.notification.service.NotificationMailDelivery.class));
		// @Value alanlarını testte setliyoruz
		setField(consumer, "idempotencyTtlSec", 900L);
		setField(consumer, "lockTtlSec", 300L);
		setField(consumer, "maxRedeliveries", 5);
		setField(consumer, "delaysMs", List.of(3000L, 10000L, 30000L));
		setField(consumer, "useRetryAfter", true);
	}
	
	private static void setField(Object target, String name, Object value) {
		try {
			Field f = MailJobConsumer.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private static MailSendRequest req() {
		return new MailSendRequest(
				"alice@example.com",
				"Subject",
				"<b>hi</b>",
				"hi",
				MailKind.OTP,
				Map.of("code", "123456")
		);
	}
	
	private static Map<String,Object> headers(int deaths) {
		if (deaths <= 0) return new HashMap<>();
		Map<String, Object> death = new HashMap<>();
		death.put("count", deaths);
		Map<String, Object> entry = new HashMap<>();
		entry.put("count", deaths);
		// basit bir x-death yapısı (helper.redeliveryCount mock'luyoruz zaten)
		return new HashMap<>(Map.of("x-death", List.of(entry)));
	}

	@Test
	void concurrentDuplicateRechecksSentAfterAcquiringLock() throws Exception {
		MailSendRequest request = req();
		AtomicBoolean sent = new AtomicBoolean(), locked = new AtomicBoolean();
		CountDownLatch duplicateRead = new CountDownLatch(1), resumeDuplicate = new CountDownLatch(1);
		when(helper.buildIdemKey(request)).thenReturn("concurrent");
		when(helper.isAlreadySent("mail:sent:concurrent")).thenAnswer(invocation -> {
			boolean snapshot = sent.get();
			if (Thread.currentThread().getName().equals("duplicate-mail") && !snapshot) {
				duplicateRead.countDown();
				if (!resumeDuplicate.await(5, TimeUnit.SECONDS)) throw new AssertionError("Duplicate barrier timed out");
			}
			return snapshot;
		});
		when(helper.acquireLock(eq("mail:lock:concurrent"), any())).thenAnswer(invocation -> locked.compareAndSet(false, true));
		doAnswer(invocation -> { sent.set(true); return null; }).when(helper).markSent(eq("mail:sent:concurrent"), any());
		doAnswer(invocation -> { locked.set(false); return null; }).when(helper).releaseLock("mail:lock:concurrent");
		ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "duplicate-mail"));
		try {
			Future<?> duplicate = worker.submit(() -> consumer.listenMailJobs(request, 102L, Map.of(), channel));
			assertThat(duplicateRead.await(5, TimeUnit.SECONDS)).isTrue();
			consumer.listenMailJobs(request, 101L, Map.of(), channel);
			assertThat(sent).isTrue();
			assertThat(locked).isFalse();
			resumeDuplicate.countDown();
			duplicate.get(5, TimeUnit.SECONDS);
			verify(mailSenderClient, times(1)).send(request.to(), request.subject(), request.textBody(), request.htmlBody());
			verify(channel).basicAck(101L, false);
			verify(channel).basicAck(102L, false);
			verifyNoInteractions(retryPublisher);
			assertThat(locked).isFalse();
		} finally {
			resumeDuplicate.countDown();
			worker.shutdownNow();
		}
	}

	@Test
	void ackFailureAfterSuccessfulSendDoesNotReleaseLockTwice() throws Exception {
		MailSendRequest request = req();
		when(helper.buildIdemKey(request)).thenReturn("ack-failure");
		when(helper.acquireLock(eq("mail:lock:ack-failure"), any())).thenReturn(true);
		doThrow(new java.io.IOException("connection lost")).when(channel).basicAck(103L, false);
		consumer.listenMailJobs(request, 103L, Map.of(), channel);
		verify(helper, times(1)).releaseLock("mail:lock:ack-failure");
		verify(mailSenderClient, times(1)).send(request.to(), request.subject(), request.textBody(), request.htmlBody());
	}

	@Test
	void alreadySentAckFailureDoesNotReleaseAnUnacquiredLock() throws Exception {
		MailSendRequest request = req();
		when(helper.buildIdemKey(request)).thenReturn("already-sent-ack");
		when(helper.isAlreadySent("mail:sent:already-sent-ack")).thenReturn(true);
		doThrow(new java.io.IOException("connection lost")).when(channel).basicAck(104L, false);
		consumer.listenMailJobs(request, 104L, Map.of(), channel);
		verify(helper, never()).acquireLock(anyString(), any());
		verify(helper, never()).releaseLock(anyString());
		verifyNoInteractions(mailSenderClient);
	}

	@Test
	void incompleteResetEnvelopeIsDiscardedBeforeClaimLookupOrProvider() throws Exception {
		var claims = mock(com.berkayb.soundconnect.auth.otp.service.OtpService.class);
		consumer.setOtpService(claims);
		when(helper.acquireLock(anyString(), any())).thenReturn(true);
		Map<String, Object> params = Map.of("requestId", "3ed81190-7d78-43d0-84cb-0eb139e243ed",
				"claimRecipient", "owned@example.invalid", "expiresAtEpochMillis", 1_800_000_000_000L);
		List<MailSendRequest> invalid = List.of(
				new MailSendRequest("owned@example.invalid", null, "html", "text", MailKind.PASSWORD_RESET, params),
				new MailSendRequest("owned@example.invalid", "reset", "", "text", MailKind.PASSWORD_RESET, params),
				new MailSendRequest("owned@example.invalid", "reset", "html", null, MailKind.PASSWORD_RESET, params));
		for (MailSendRequest request : invalid) consumer.listenMailJobs(request, 7L, Map.of(), channel);
		verify(channel, times(3)).basicAck(7L, false);
		verifyNoInteractions(claims, mailSenderClient, retryPublisher);
		verify(helper, never()).markSent(anyString(), any());
	}

	private static Map<String, Object> resetParams(String recipient) {
		var params = new HashMap<String, Object>();
		params.put("requestId", "3ed81190-7d78-43d0-84cb-0eb139e243ed");
		params.put("expiresAtEpochMillis", 1_800_000_000_000L);
		params.put("claimRecipient", recipient);
		return params;
	}

	static Stream<Arguments> malformedResetEnvelopes() {
		var cases = new ArrayList<Arguments>();
		for (String recipient : new String[] {null, "", " ", "owned", "owned@", "@example.test",
				"owned@\t", " \t@example.test"}) {
			cases.add(Arguments.of(recipient, resetParams(recipient)));
		}
		cases.add(Arguments.of("owned@example.test", null));
		cases.add(Arguments.of("owned@example.test", Map.of()));
		var missing = resetParams("owned@example.test");
		missing.remove("claimRecipient");
		cases.add(Arguments.of("owned@example.test", missing));
		for (String claim : new String[] {null, "", "owned@", "owned@@example.test", "owned @example.test"}) {
			cases.add(Arguments.of("owned@example.test", resetParams(claim)));
		}
		return cases.stream();
	}

	private MailJobConsumer realHelperConsumer(com.berkayb.soundconnect.auth.otp.service.OtpService claims) {
		var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		var values = (org.springframework.data.redis.core.ValueOperations<String, String>)
				mock(org.springframework.data.redis.core.ValueOperations.class);
		lenient().when(redis.opsForValue()).thenReturn(values);
		lenient().when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
		var actual = new MailJobConsumer(mailSenderClient, new MailJobHelper(redis), retryPublisher,
				mock(com.berkayb.soundconnect.modules.notification.service.NotificationMailDelivery.class));
		actual.setOtpService(claims);
		setField(actual, "idempotencyTtlSec", 900L);
		setField(actual, "lockTtlSec", 300L);
		setField(actual, "maxRedeliveries", 5);
		setField(actual, "delaysMs", List.of(3000L));
		return actual;
	}

	@ParameterizedTest(name = "malformed reset envelope {index}")
	@MethodSource("malformedResetEnvelopes")
	void malformedResetWithRealHelperIsAckedBeforeAuthoritativeClaimLookup(
			String recipient, Map<String, Object> params) throws Exception {
		var claims = mock(com.berkayb.soundconnect.auth.otp.service.OtpService.class);
		var actual = realHelperConsumer(claims);
		var request = new MailSendRequest(recipient, "reset", "html", "text", MailKind.PASSWORD_RESET, params);
		assertThatCode(() -> actual.listenMailJobs(request, 71L, Map.of(), channel))
				.doesNotThrowAnyException();
		verifyNoInteractions(claims, mailSenderClient, retryPublisher);
		verify(channel).basicAck(71L, false);
		verifyNoMoreInteractions(channel);
	}

	@ParameterizedTest(name = "supported recipient form {index}")
	@ValueSource(strings = {"\"first last\"@example.test", "\"first@last\"@example.test", "\u00f6mer@example.test"})
	void recipientShapeGuardDoesNotInventNewEmailPolicy(String recipient) throws Exception {
		var claims = mock(com.berkayb.soundconnect.auth.otp.service.OtpService.class);
		var actual = realHelperConsumer(claims);
		when(claims.authorizePasswordResetMail(eq(recipient), anyString(), anyLong())).thenReturn(true);
		var request = new MailSendRequest(recipient, "reset", "html", "text", MailKind.PASSWORD_RESET, resetParams(recipient));
		actual.listenMailJobs(request, 72L, Map.of(), channel);
		verify(mailSenderClient).send(recipient, "reset", "text", "html");
		verify(channel).basicAck(72L, false);
		verifyNoInteractions(retryPublisher);
	}
	
	@Test
	@DisplayName("alreadySent → ACK ve çık")
	void alreadySent_ack() throws Exception {
		MailSendRequest r = req();
		long tag = 10L;
		
		when(helper.buildIdemKey(r)).thenReturn("idem-1");
		when(helper.isAlreadySent("mail:sent:idem-1")).thenReturn(true);
		
		consumer.listenMailJobs(r, tag, headers(0), channel);
		
		verify(channel).basicAck(tag, false);
		verifyNoInteractions(mailSenderClient, retryPublisher);
		verify(helper, never()).acquireLock(anyString(), any());
	}
	
	@Test
	@DisplayName("lock alınamadı → requeue(true)")
	void lockBusy_requeue() throws Exception {
		MailSendRequest r = req();
		long tag = 11L;
		
		when(helper.buildIdemKey(r)).thenReturn("idem-2");
		when(helper.isAlreadySent("mail:sent:idem-2")).thenReturn(false);
		when(helper.acquireLock("mail:lock:idem-2", Duration.ofSeconds(300L))).thenReturn(false);
		
		consumer.listenMailJobs(r, tag, headers(0), channel);
		
		verify(channel).basicReject(tag, true);
		verifyNoInteractions(mailSenderClient, retryPublisher);
	}
	
	@Test
	@DisplayName("başarılı gönderim → markSent + releaseLock + ACK")
	void success_flow_ack() throws Exception {
		MailSendRequest r = req();
		long tag = 12L;
		
		when(helper.buildIdemKey(r)).thenReturn("idem-3");
		when(helper.isAlreadySent("mail:sent:idem-3")).thenReturn(false);
		when(helper.acquireLock("mail:lock:idem-3", Duration.ofSeconds(300L))).thenReturn(true);
		
		consumer.listenMailJobs(r, tag, headers(0), channel);
		
		verify(mailSenderClient).send(eq(r.to()), eq(r.subject()), eq(r.textBody()), eq(r.htmlBody()));
		verify(helper).markSent(eq("mail:sent:idem-3"), eq(Duration.ofSeconds(900L)));
		verify(helper).releaseLock("mail:lock:idem-3");
		verify(channel).basicAck(tag, false);
		verifyNoInteractions(retryPublisher);
	}
	
	@Test
	@DisplayName("transient hata + limit içinde → retry publish + ACK")
	void transient_error_retry_and_ack() throws Exception {
		MailSendRequest r = req();
		long tag = 13L;
		Map<String,Object> hdrs = headers(1);
		
		when(helper.buildIdemKey(r)).thenReturn("idem-4");
		when(helper.isAlreadySent("mail:sent:idem-4")).thenReturn(false);
		when(helper.acquireLock("mail:lock:idem-4", Duration.ofSeconds(300L))).thenReturn(true);
		
		// mail send sırasında hata
		RuntimeException ex = new RuntimeException("io-timeout");
		doThrow(ex).when(mailSenderClient).send(anyString(), anyString(), any(), any());
		
		// retry değerlendirmeleri
		when(helper.retryAttempt(hdrs)).thenReturn(1);
		when(helper.isTransient(ex)).thenReturn(true);
		when(helper.chooseDelayMs(eq(ex), eq(1), anyList(), eq(true))).thenReturn(5000L);
		
		consumer.listenMailJobs(r, tag, hdrs, channel);
		
		// Retry publish çağrılmalı
		verify(retryPublisher).publishWithDelay(eq(r), eq(5000L), eq(2), contains("attempt=2"));
		// Kilit serbest bırakılmalı
		verify(helper).releaseLock("mail:lock:idem-4");
		
		// publish başarılı olduğunda ACK bekleniyor (kodda ACK publish'ten sonra)
		verify(channel).basicAck(tag, false);
	}
	
	@Test
	@DisplayName("kalıcı hata (ör. 4xx) → DLQ (reject false) + lock release")
	void permanent_error_goes_dlq() throws Exception {
		MailSendRequest r = req();
		long tag = 14L;
		
		when(helper.buildIdemKey(r)).thenReturn("idem-5");
		when(helper.isAlreadySent("mail:sent:idem-5")).thenReturn(false);
		when(helper.acquireLock("mail:lock:idem-5", Duration.ofSeconds(300L))).thenReturn(true);
		
		RuntimeException ex = new RuntimeException("bad-request");
		doThrow(ex).when(mailSenderClient).send(anyString(), anyString(), any(), any());
		
		when(helper.isTransient(ex)).thenReturn(false); // kalıcı
		
		consumer.listenMailJobs(r, tag, headers(3), channel);
		
		// DLQ yönlendirmesinde reject(false)
		verify(helper).logErrorForSend(eq(r), eq(ex), eq(false));
		verify(helper).releaseLock("mail:lock:idem-5");
		verify(channel).basicReject(tag, false);
		
		verifyNoInteractions(retryPublisher);
	}

	@Test
	@DisplayName("retry publish broker confirm basarisizsa original mesaj ACK edilmez")
	void retryPublishFailure_requeuesOriginal() throws Exception {
		MailSendRequest r = req();
		long tag = 15L;
		Map<String, Object> hdrs = headers(0);

		when(helper.buildIdemKey(r)).thenReturn("idem-6");
		when(helper.isAlreadySent("mail:sent:idem-6")).thenReturn(false);
		when(helper.acquireLock("mail:lock:idem-6", Duration.ofSeconds(300L))).thenReturn(true);
		RuntimeException sendFailure = new RuntimeException("timeout");
		doThrow(sendFailure).when(mailSenderClient).send(anyString(), anyString(), any(), any());
		when(helper.retryAttempt(hdrs)).thenReturn(0);
		when(helper.isTransient(sendFailure)).thenReturn(true);
		when(helper.chooseDelayMs(sendFailure, 0, List.of(3000L, 10000L, 30000L), true))
				.thenReturn(3000L);
		doThrow(new RuntimeException("broker nack"))
				.when(retryPublisher).publishWithDelay(r, 3000L, 1, "attempt=1");

		consumer.listenMailJobs(r, tag, hdrs, channel);

		verify(channel).basicReject(tag, true);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
	}

	@Test
	@DisplayName("persisted retry attempt max degerindeyse mesaj DLQ'ya gider")
	void maxRetryAttempt_goesToDlq() throws Exception {
		MailSendRequest r = req();
		long tag = 16L;
		Map<String, Object> hdrs = Map.of(MailJobHelper.RETRY_ATTEMPT_HEADER, 5);

		when(helper.buildIdemKey(r)).thenReturn("idem-7");
		when(helper.isAlreadySent("mail:sent:idem-7")).thenReturn(false);
		when(helper.acquireLock("mail:lock:idem-7", Duration.ofSeconds(300L))).thenReturn(true);
		RuntimeException sendFailure = new RuntimeException("timeout");
		doThrow(sendFailure).when(mailSenderClient).send(anyString(), anyString(), any(), any());
		when(helper.retryAttempt(hdrs)).thenReturn(5);
		when(helper.isTransient(sendFailure)).thenReturn(true);

		consumer.listenMailJobs(r, tag, hdrs, channel);

		verify(channel).basicReject(tag, false);
		verifyNoInteractions(retryPublisher);
	}
}
