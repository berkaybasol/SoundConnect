package com.berkayb.soundconnect.shared.mail;

/**
 * Transactional e-postalar icin genel gonderim portu
 * MailerSend gibi saglayicilarin adapter'i bu interface'i uygular
 *
 */
public interface MailSenderClient {
	/**
	 * E-postayı gönderir.
	 *
	 * @param to        Alıcı e-posta (zorunlu)
	 * @param subject   Konu (zorunlu)
	 * @param textBody  Düz metin içerik (opsiyonel; null olabilir)
	 * @param htmlBody  HTML içerik (opsiyonel; null olabilir)
	 */
	void send(String to, String subject, String textBody, String htmlBody);
}