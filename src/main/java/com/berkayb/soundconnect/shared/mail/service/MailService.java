package com.berkayb.soundconnect.shared.mail.service;

public interface MailService {
	void sendVerificationMail(String to, String code);
}