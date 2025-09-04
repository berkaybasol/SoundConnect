package com.berkayb.soundconnect.shared.mail;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;

/**
 * Tum modullerin herhangi bir mail isini siraya atmak icin kullanacagi generic port.
 * Sadece MailSendRequest ile calisir. baska overload'a izin vermez.
 */
public interface MailProducer {
	void send(MailSendRequest request);
}