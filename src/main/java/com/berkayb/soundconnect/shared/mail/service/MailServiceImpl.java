package com.berkayb.soundconnect.shared.mail.service;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.MailSenderClient; // -> eklendi
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MailerSend ile e-posta DOĞRULAMA (OTP) gönderimi.
 * HTTP çağrılarını doğrudan burada yapmak yerine MailSenderClient port'una devrediyoruz.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailServiceImpl implements MailService {
	
	private final MailSenderClient mailSenderClient; // -> eklendi
	
	@Value("${mailersend.otp-validity-minutes:5}")
	private int otpValidityMinutes;
	
	@Override
	public void sendVerificationMail(String to, String code) {
		String subject = "E-posta Doğrulama Kodunuz";
		
		String html = """
            <p>Merhaba,</p>
            <p>Hesabınızı doğrulamak için aşağıdaki <b>6 haneli</b> kodu uygulamaya girin:</p>
            <h2 style='letter-spacing:5px; font-size: 2em;'>%s</h2>
            <p>Kodunuz <b>%d dakika</b> boyunca geçerlidir.</p>
            <p>Eğer bu isteği siz yapmadıysanız, lütfen bu maili dikkate almayın.</p>
            <p>Teşekkürler,<br/>SoundConnect Ekibi &#10084;&#65039;</p>
            """.formatted(code, otpValidityMinutes);
		
		try {
			mailSenderClient.send(to, subject, null, html); // -> eklendi (artık tek port üzerinden gönderiyoruz)
			log.info("Verification mail sent via MailSenderClient to email={}", to);
		} catch (Exception e) {
			log.error("Failed to send verification mail to email={}", to, e);
			throw new SoundConnectException(
					ErrorType.MAIL_QUEUE_ERROR,
					List.of("Mail adresi: " + to, "Hata: " + e.getMessage())
			);
		}
	}
}