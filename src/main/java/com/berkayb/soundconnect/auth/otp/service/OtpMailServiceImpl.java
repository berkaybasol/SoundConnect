package com.berkayb.soundconnect.auth.otp.service;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailContentBuilder;
import com.berkayb.soundconnect.shared.util.EmailUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MailerSend ile e-posta DOĞRULAMA (OTP) gönderimi.
 * Amac:
 * - Mail icerigini MailContentBuilder ile disaridan hazirla.
 * - MailSenderClient ile asenkron mail gonderimini yonet
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpMailServiceImpl implements OtpMailService {
	
	private final MailSenderClient mailSenderClient;
	private final MailContentBuilder mailContentBuilder; // Artık builder kullanıyoruz!
	
	@Value("${otp.ttl.minutes:${mailersend.otp-validity-minutes:3}}")
	private int otpValidityMinutes;
	
	/**
	 * Doğrulama maili gönderir (OTP).
	 * @param to Kullanıcının mail adresi
	 * @param code 6 haneli doğrulama kodu
	 */
	@Override
	public void sendVerificationMail(String to, String code) {
		String subject = "E-posta Doğrulama Kodunuz";
		// HTML body'yi builder ile dışarıdan template olarak üret!
		String html = mailContentBuilder.buildVerificationMail(code, otpValidityMinutes);
		try {
			// MailSenderClient ile gönder (asenkron, provider agnostic)
			mailSenderClient.send(to, subject, null, html);
			log.info("Verification mail sent via MailSenderClient to email={}", EmailUtils.maskForLog(to));
		} catch (Exception e) {
			log.error("Failed to send verification mail to email={}", EmailUtils.maskForLog(to), e);
			throw new SoundConnectException(
					ErrorType.MAIL_QUEUE_ERROR,
					List.of("Mail adresi: " + to, "Hata: " + e.getMessage())
			);
		}
	}
}
