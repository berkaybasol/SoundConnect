package com.berkayb.soundconnect.shared.mail.dto;

import com.berkayb.soundconnect.shared.mail.enums.MailKind;

import java.util.Map;

// tum modullerin kullanacagi generic mail is tanimi.
public record MailSendRequest(
		String to,
		String subject,
		String htmlBody,
		String textBody,
		MailKind kind,
		Map<String, Object> params
) {
}