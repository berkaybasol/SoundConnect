package com.berkayb.soundconnect.shared.mail.helper;

import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetMailTemplateTest {

	@Test
	void rendersPasswordResetCodeAndValidityFromClasspathTemplate() {
		ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
		resolver.setPrefix("templates/");
		resolver.setSuffix(".html");
		resolver.setTemplateMode("HTML");
		resolver.setCharacterEncoding("UTF-8");
		resolver.setCacheable(false);
		SpringTemplateEngine engine = new SpringTemplateEngine();
		engine.setTemplateResolver(resolver);

		String html = new MailContentBuilder(engine)
				.buildPasswordResetMail("123456", 3);

		assertThat(html)
				.contains("123456")
				.contains("3 dakika")
				.contains("yalnızca bir kez")
				.doesNotContain("[[${code}]]");
	}
}
