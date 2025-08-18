package com.berkayb.soundconnect.shared.mail;

public interface MailProducer {
	void sendVerificationMail(String email, String code);
}