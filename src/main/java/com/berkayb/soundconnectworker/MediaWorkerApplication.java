package com.berkayb.soundconnectworker;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import com.berkayb.soundconnectworker.persistence.MediaWorkerAssetRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Native-media process boundary.
 *
 * <p>This entry point deliberately lives outside {@code com.berkayb.soundconnect}
 * so the API application's broad component scan can never discover it. In the
 * other direction this context has no component scan at all: every executable
 * capability is explicitly imported by {@link MediaWorkerComponentsConfiguration}.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
		RabbitAutoConfiguration.class,
		SecurityAutoConfiguration.class,
		UserDetailsServiceAutoConfiguration.class,
		OAuth2ClientAutoConfiguration.class,
		MailSenderAutoConfiguration.class,
		RedisAutoConfiguration.class,
		RedisRepositoriesAutoConfiguration.class,
		ThymeleafAutoConfiguration.class,
		ServletWebServerFactoryAutoConfiguration.class,
		WebMvcAutoConfiguration.class,
		WebFluxAutoConfiguration.class,
		ErrorMvcAutoConfiguration.class,
		WebSocketServletAutoConfiguration.class
})
@EntityScan(basePackageClasses = MediaAsset.class)
@EnableJpaRepositories(basePackageClasses = MediaWorkerAssetRepository.class)
@EnableScheduling
@Import({
		JpaAuditingConfig.class,
		MediaWorkerComponentsConfiguration.class
})
public class MediaWorkerApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(MediaWorkerApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		// The dedicated artifact must never accidentally start with monolith defaults.
		application.setAdditionalProfiles("media-worker");
		application.run(args);
	}
}
