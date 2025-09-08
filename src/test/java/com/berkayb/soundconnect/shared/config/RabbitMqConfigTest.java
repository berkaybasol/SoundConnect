package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RabbitMqConfigTest {
	
	@Test
	@DisplayName("Jackson2JsonMessageConverter JSON round-trip (toMessage → fromMessage)")
	void json_roundtrip_ok() {
		RabbitMqConfig cfg = new RabbitMqConfig();
		Jackson2JsonMessageConverter conv = cfg.jackson2JsonMessageConverter();
		assertThat(conv).isNotNull();
		
		// TEST İÇİN: mapper'ı trust-all yap (prod koduna dokunmuyoruz)
		var mapper = (DefaultJackson2JavaTypeMapper) deepGet(conv, "javaTypeMapper");
		mapper.setTrustedPackages("*");
		
		MailSendRequest original = new MailSendRequest(
				"alice@example.com", "Verify", "<b>hi</b>", "hi",
				MailKind.OTP, Map.of("code", "123456")
		);
		
		var props = new MessageProperties();
		Message msg = conv.toMessage(original, props);
		
		assertThat(msg.getMessageProperties().getContentType()).contains("json");
		
		Object restored = conv.fromMessage(msg, MailSendRequest.class);
		assertThat(restored)
				.isInstanceOf(MailSendRequest.class)
				.isEqualTo(original);
	}
	
	private static Object deepGet(Object target, String field) {
		Class<?> c = target.getClass();
		while (c != null) {
			try {
				var f = c.getDeclaredField(field);
				f.setAccessible(true);
				return f.get(target);
			} catch (NoSuchFieldException e) { c = c.getSuperclass(); }
			catch (Exception e) { throw new RuntimeException(e); }
		}
		throw new RuntimeException(new NoSuchFieldException(field));
	}
}