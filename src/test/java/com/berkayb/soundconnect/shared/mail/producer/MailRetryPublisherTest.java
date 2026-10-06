package com.berkayb.soundconnect.shared.mail.producer;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailRetryPublisherTest {

	@Mock
	private RabbitTemplate rabbitTemplate;

	private MailRetryPublisher publisher;

	@BeforeEach
	void setUp() {
		publisher = new MailRetryPublisher(new RabbitTemplate(mock(ConnectionFactory.class)));
		setField("delayedExchange", "mail.delayed");
		setField("routingKey", "mail.send");
		setField("mailQueueName", "mail.queue");
		setField("confirmTimeoutSec", 1L);
		publisher.validateConfiguration();
		setField("delayedRabbitTemplate", rabbitTemplate);
	}

	@Test
	void persistsIncrementedAttemptAndWaitsForAck() {
		AtomicReference<Message> publishedMessage = new AtomicReference<>();
		doAnswer(invocation -> {
			MessagePostProcessor postProcessor = invocation.getArgument(3);
			Message message = postProcessor.postProcessMessage(
					new Message(new byte[0], new MessageProperties()));
			publishedMessage.set(message);
			CorrelationData correlationData = invocation.getArgument(4);
			correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbitTemplate).convertAndSend(
				eq("mail.delayed"),
				eq("mail.send"),
				any(MailSendRequest.class),
				any(MessagePostProcessor.class),
				any(CorrelationData.class));

		publisher.publishWithDelay(request(), 1_000L, 3, "attempt=3");

		MessageProperties properties = publishedMessage.get().getMessageProperties();
		assertThat(properties.<Integer>getHeader(MailJobHelper.RETRY_ATTEMPT_HEADER)).isEqualTo(3);
		assertThat(properties.<Boolean>getHeader(MailJobHelper.RETRY_MARKER_HEADER)).isTrue();
		assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
		assertThat(properties.getCorrelationId()).isNotBlank();
		assertThat(((Number) properties.getHeader("x-delay")).longValue())
				.isBetween(1_000L, 1_100L);
	}

	@Test
	void nackFailsTheRetryPublish() {
		doAnswer(invocation -> {
			CorrelationData correlationData = invocation.getArgument(4);
			correlationData.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
			return null;
		}).when(rabbitTemplate).convertAndSend(
				any(String.class), any(String.class), any(),
				any(MessagePostProcessor.class), any(CorrelationData.class));

		assertThatThrownBy(() -> publisher.publishWithDelay(request(), 1_000L, 1, "attempt=1"))
				.isInstanceOf(AmqpException.class);
	}

	@Test
	void returnedMessageFailsTheRetryPublish() {
		doAnswer(invocation -> {
			CorrelationData correlationData = invocation.getArgument(4);
			correlationData.setReturned(new ReturnedMessage(
					new Message(new byte[0], new MessageProperties()),
					312,
					"NO_ROUTE",
					"mail.delayed",
					"mail.send"));
			correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbitTemplate).convertAndSend(
				any(String.class), any(String.class), any(),
				any(MessagePostProcessor.class), any(CorrelationData.class));

		assertThatThrownBy(() -> publisher.publishWithDelay(request(), 1_000L, 1, "attempt=1"))
				.isInstanceOf(AmqpException.class);
	}

	@Test
	void missingStorageConfirmationTimesOutInsteadOfAcknowledgingOriginal() {
		assertThatThrownBy(() -> publisher.publishWithDelay(request(), 1_000L, 1, "attempt=1"))
				.isInstanceOf(AmqpException.class)
				.hasMessage("Retry publish confirmation failed")
				.hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
	}

	@Test
	void passiveQueueFailurePreventsPublish() {
		when(rabbitTemplate.execute(any(ChannelCallback.class)))
				.thenThrow(new AmqpException("destination unavailable"));
		assertThatThrownBy(() -> publisher.publishWithDelay(request(), 1_000L, 1, "attempt=1"))
				.isInstanceOf(AmqpException.class);
		verify(rabbitTemplate, never()).convertAndSend(
				any(String.class), any(String.class), any(),
				any(MessagePostProcessor.class), any(CorrelationData.class));
	}

	@Test
	void retryLogRecipientMaskIsTotalWithoutChangingNormalPrivacy() throws Exception {
		var mask = MailRetryPublisher.class.getDeclaredMethod("maskForLog", String.class);
		mask.setAccessible(true);
		for (String malformed : new String[] {"", " ", "@", "owned", "owned@", "@@", "owned@@"}) {
			assertThat(mask.invoke(publisher, malformed)).isNotNull();
		}
		assertThat(mask.invoke(publisher, "owned@")).isEqualTo("***");
		assertThat(mask.invoke(publisher, new Object[] {null})).isEqualTo("null");
		assertThat(mask.invoke(publisher, "owned@example.test")).isEqualTo("o***@e***.test");
		assertThat(mask.invoke(publisher, "owned@domain")).isEqualTo("o***@d***");
	}

	private MailSendRequest request() {
		return new MailSendRequest(
				"alice@example.com",
				"subject",
				"<p>body</p>",
				"body",
				MailKind.GENERIC,
				Map.of());
	}

	private void setField(String name, Object value) {
		try {
			Field field = MailRetryPublisher.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(publisher, value);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}
}
