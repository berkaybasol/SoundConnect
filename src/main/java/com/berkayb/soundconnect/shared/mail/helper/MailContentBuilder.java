package com.berkayb.soundconnect.shared.mail.helper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Mail sablonlarini disaridan (resources/templates) okuyup parametrelerle doldurup HTML body olarak geri doner.
 * Amac:
 * - Kodda string ile bogusmadan surdurulebilir template yonetimi saglamak
 * - Farkli mail turleri icin parametreli coklu sablon destegi saglamak.
 */

@Service
@RequiredArgsConstructor
public class MailContentBuilder {
	private final TemplateEngine templateEngine;
	
	/**
	 * OTP maili icin parametreli HTML body olusturur.
	 * @param code 6 haneli OTP kodu
	 * @param validityMinutes kodun gecerlilik suresi (dk)
	 */
	public String buildVerificationMail(String code, int validityMinutes) {
		Context context = new Context();
		context.setVariable("code", code);
		context.setVariable("validityMinutes", validityMinutes);
		// "mail/verification.html" -> resources/templates/mail/verification.html
		return templateEngine.process("mail/verification.html", context);
	}
	
	//FIXME baska template'ler icin burada ek method acilcakk
}