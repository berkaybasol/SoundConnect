package com.berkayb.soundconnect.shared.mail.producer;

import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailProducerImplTest {
	
	private final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
	private final MailJobHelper helper = new MailJobHelper(null);
	
	private MailProducerImpl newProducerWithConfirmTimeoutSeconds(long seconds) {
		MailProducerImpl impl = new MailProducerImpl(rabbitTemplate, helper);
		setField(impl, "mailExchange", "mail.exchange");
		setField(impl, "mailRoutingKey", "mail.send");
		setField(impl, "confirmTimeoutSec", seconds);
		return impl;
	}
	
	private static void setField(Object target, String field, Object value) {
		try {
			Field f = MailProducerImpl.class.getDeclaredField(field);
			f.setAccessible(true);
			f.set(target, value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private static MailSendRequest sampleReq() {
		return new MailSendRequest(
				"alice@example.com", "Hello", "<b>hi</b>", "hi", MailKind.GENERIC, null
		);
	}
	
	@Test
	@DisplayName("ACK → publish başarılı")
	void send_ack_success() {
		MailProducerImpl producer = newProducerWithConfirmTimeoutSeconds(2);
		
		// 3. parametreyi MailSendRequest olarak belirterek overload'ı sabitliyoruz
		doAnswer(inv -> {
			CorrelationData cd = inv.getArgument(3, CorrelationData.class);
			cd.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbitTemplate)
		  .convertAndSend(eq("mail.exchange"), eq("mail.send"), any(MailSendRequest.class), any(CorrelationData.class));
		
		producer.send(sampleReq());
		
		verify(rabbitTemplate, times(1))
				.convertAndSend(eq("mail.exchange"), eq("mail.send"), any(MailSendRequest.class), any(CorrelationData.class));
	}
	
	@Test
	@DisplayName("NACK → SoundConnectException fırlatır (genel mesaj)")
	void send_nack_throws() {
		MailProducerImpl producer = newProducerWithConfirmTimeoutSeconds(2);
		
		doAnswer(inv -> {
			CorrelationData cd = inv.getArgument(3, CorrelationData.class);
			cd.getFuture().complete(new CorrelationData.Confirm(false, "broker-nack"));
			return null;
		}).when(rabbitTemplate)
		  .convertAndSend(eq("mail.exchange"), eq("mail.send"), any(MailSendRequest.class), any(CorrelationData.class));
		
		assertThatThrownBy(() -> producer.send(sampleReq()))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining("Mail could not be queued"); // <-- burada "Broker NACK" değil
	}
	
	@Test
	@DisplayName("Returned (route edilemedi) → SoundConnectException fırlatır (genel mesaj)")
	void send_returned_throws() {
		MailProducerImpl producer = newProducerWithConfirmTimeoutSeconds(2);
		
		doAnswer(inv -> {
			CorrelationData cd = inv.getArgument(3, CorrelationData.class);
			cd.getFuture().complete(new CorrelationData.Confirm(true, null));
			Message msg = new Message("x".getBytes(), new MessageProperties());
			ReturnedMessage rm = new ReturnedMessage(msg, 312, "NO_ROUTE", "mail.exchange", "mail.send");
			cd.setReturned(rm);
			return null;
		}).when(rabbitTemplate)
		  .convertAndSend(eq("mail.exchange"), eq("mail.send"), any(MailSendRequest.class), any(CorrelationData.class));
		
		assertThatThrownBy(() -> producer.send(sampleReq()))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining("Mail could not be queued"); // <-- aynı genel mesaj
	}
	
	@Test
	@DisplayName("convertAndSend sırasında exception → SoundConnectException fırlatır (genel mesaj)")
	void send_convert_exception_throws() {
		MailProducerImpl producer = newProducerWithConfirmTimeoutSeconds(2);
		
		doThrow(new RuntimeException("boom"))
				.when(rabbitTemplate)
				.convertAndSend(eq("mail.exchange"), eq("mail.send"), any(MailSendRequest.class), any(CorrelationData.class));
		
		assertThatThrownBy(() -> producer.send(sampleReq()))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining("Mail could not be queued"); // <-- "Publish failed" yerine genel mesaj
	}
}