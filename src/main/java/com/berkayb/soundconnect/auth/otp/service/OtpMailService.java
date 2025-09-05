package com.berkayb.soundconnect.auth.otp.service;

public interface OtpMailService {
	void sendVerificationMail(String to, String code);
}