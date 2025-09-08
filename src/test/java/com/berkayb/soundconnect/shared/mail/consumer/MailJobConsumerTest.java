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
		consumer = new MailJobConsumer(mailSenderClient, helper, retryPublisher);
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
		when(helper.redeliveryCount(hdrs)).thenReturn(1);
		when(helper.isTransient(ex)).thenReturn(true);
		when(helper.chooseDelayMs(eq(ex), eq(1), anyList(), eq(true))).thenReturn(5000L);
		
		consumer.listenMailJobs(r, tag, hdrs, channel);
		
		// Retry publish çağrılmalı
		verify(retryPublisher).publishWithDelay(eq(r), eq(5000L), contains("deaths=1"));
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
		
		when(helper.redeliveryCount(anyMap())).thenReturn(3);
		when(helper.isTransient(ex)).thenReturn(false); // kalıcı
		
		consumer.listenMailJobs(r, tag, headers(3), channel);
		
		// DLQ yönlendirmesinde reject(false)
		verify(helper).logErrorForSend(eq(r), eq(ex), eq(false));
		verify(helper).releaseLock("mail:lock:idem-5");
		verify(channel).basicReject(tag, false);
		
		verifyNoInteractions(retryPublisher);
	}
}