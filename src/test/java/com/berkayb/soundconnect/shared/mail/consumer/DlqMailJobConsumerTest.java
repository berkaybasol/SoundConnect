package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DlqMailJobConsumerTest {
	
	@Mock
	private MailJobHelper helper;
	
	@Test
	@DisplayName("DLQ: MailSendRequest payload → maskEmail çağrılır")
	void dlq_with_mailRequest() {
		DlqMailJobConsumer c = new DlqMailJobConsumer(helper);
		MailSendRequest req = new MailSendRequest(
				"bob@example.com", "Sbj", "<b>h</b>", "h",
				MailKind.GENERIC, Map.of("x", 1)
		);
		
		c.listenMailDlq(req);
		
		verify(helper, times(1)).maskEmail("bob@example.com");
		verifyNoMoreInteractions(helper);
	}
	
	@Test
	@DisplayName("DLQ: Map payload → maskEmail çağrılır (to alanı okunur)")
	void dlq_with_mapPayload() {
		DlqMailJobConsumer c = new DlqMailJobConsumer(helper);
		Map<String,Object> map = Map.of(
				"to", "carol@example.com",
				"subject", "Subj",
				"kind", "OTP",
				"any", 123
		);
		
		c.listenMailDlq(map);
		
		verify(helper, times(1)).maskEmail("carol@example.com");
		verifyNoMoreInteractions(helper);
	}
	
	@Test
	@DisplayName("DLQ: Raw payload (String vs) → helper çağrısı yok")
	void dlq_with_rawPayload() {
		DlqMailJobConsumer c = new DlqMailJobConsumer(helper);
		
		c.listenMailDlq("raw-string-payload");
		
		verifyNoInteractions(helper);
	}
}