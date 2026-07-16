package com.berkayb.soundconnectworker;

import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.image.FfmpegImageThumbnailProcessor;
import com.berkayb.soundconnect.modules.media.image.ImageVariantBackfillScheduler;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetReferenceRepository;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.transcode.VideoHlsListener;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnectworker.persistence.MediaWorkerAssetRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
		classes = MediaWorkerApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"SOUNDCONNECT_MEDIA_WORKER_POSTGRES_URL=jdbc:h2:mem:media-worker-boundary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
				"SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME=sa",
				"SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD=",
				"spring.datasource.driver-class-name=org.h2.Driver",
				"spring.jpa.hibernate.ddl-auto=create-drop",
				"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
				"SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_HOST=127.0.0.1",
				"SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME=worker-test",
				"SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD=worker-test",
				"spring.rabbitmq.listener.simple.auto-startup=false",
				"SOUNDCONNECT_MEDIA_WORKER_S3_PUBLIC_BUCKET=worker-public-test",
				"SOUNDCONNECT_MEDIA_WORKER_S3_PRIVATE_BUCKET=worker-private-test",
				"SOUNDCONNECT_MEDIA_WORKER_AWS_REGION=eu-central-1",
				"SOUNDCONNECT_MEDIA_WORKER_CDN_BASE_URL=https://cdn.test.invalid",
				"soundconnect.media-worker.health-file-enabled=false",
				"media.image-variants.backfill.initial-delay=PT1H"
		}
)
@ActiveProfiles("media-worker")
class MediaWorkerContextBoundaryTest {

	@Autowired ApplicationContext context;
	@Autowired EntityManagerFactory entityManagerFactory;

	@Test
	void contextContainsOnlyNativeMediaCapabilitiesAndOperationalHealth() {
		assertThat(context.getBeansOfType(VideoHlsListener.class)).hasSize(1);
		assertThat(context.getBeansOfType(FfmpegImageThumbnailProcessor.class)).hasSize(1);
		assertThat(context.getBeansOfType(ImageVariantBackfillScheduler.class)).hasSize(1);
		assertThat(context.getBeansOfType(MediaWorkerAssetRepository.class)).hasSize(1);
		assertThat(context.getBeansOfType(MediaAssetRepository.class)).hasSize(1);
		assertThat(context.getBeansOfType(MediaWorkerDependencyHealthIndicator.class)).hasSize(1);

		Set<Class<?>> managedEntities = entityManagerFactory.getMetamodel().getEntities().stream()
				.map(type -> type.getJavaType())
				.collect(Collectors.toSet());
		assertThat(managedEntities).containsExactly(MediaAsset.class);
	}

	@Test
	void contextCannotExposeApiAuthenticationMailRedisOrWebTransport() {
		assertThat(context.getBeanNamesForAnnotation(RestController.class)).isEmpty();
		assertThat(context.getBeansOfType(ServletWebServerFactory.class)).isEmpty();
		assertThat(context.getBeansOfType(DispatcherServlet.class)).isEmpty();
		assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
		assertThat(context.getBeansOfType(AuthService.class)).isEmpty();
		assertThat(context.getBeansOfType(MailSenderClient.class)).isEmpty();
		assertThat(context.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
		assertThat(context.getBeansOfType(RabbitTemplate.class)).isEmpty();
		assertThat(context.getBeansOfType(MediaAssetReferenceRepository.class)).isEmpty();
	}
}
