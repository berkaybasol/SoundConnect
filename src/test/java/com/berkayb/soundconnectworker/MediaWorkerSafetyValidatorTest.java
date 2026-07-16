package com.berkayb.soundconnectworker;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaWorkerSafetyValidatorTest {
	private static final List<String> EXPECTED_FORBIDDEN_API_ENVIRONMENT = List.of(
			"SOUNDCONNECT_JWT_SECRETKEY",
			"MAILERSEND_API_KEY",
			"GOOGLE_CLIENT_ID",
			"GOOGLE_CLIENT_SECRET",
			"SPOTIFY_CLIENT_ID",
			"SPOTIFY_CLIENT_SECRET",
			"S3_ACCESS_KEY",
			"S3_SECRET_KEY",
			"AWS_ACCESS_KEY_ID",
			"AWS_SECRET_ACCESS_KEY",
			"AWS_SESSION_TOKEN",
			"AWS_PROFILE",
			"SOUNDCONNECT_POSTGRES_URL",
			"SOUNDCONNECT_POSTGRES_USERNAME",
			"SOUNDCONNECT_POSTGRES_PASSWORD",
			"SOUNDCONNECT_POSTGRE_URL",
			"SOUNDCONNECT_POSTGRE_USERNAME",
			"SOUNDCONNECT_POSTGRE_PASSWORD",
			"SPRING_DATASOURCE_URL",
			"SPRING_DATASOURCE_USERNAME",
			"SPRING_DATASOURCE_PASSWORD",
			"SPRING_DATASOURCE_JNDI_NAME",
			"SPRING_DATASOURCE_HIKARI_JDBC_URL",
			"SPRING_DATASOURCE_HIKARI_USERNAME",
			"SPRING_DATASOURCE_HIKARI_PASSWORD",
			"SPRING_RABBITMQ_ADDRESSES",
			"SPRING_RABBITMQ_USERNAME",
			"SPRING_RABBITMQ_PASSWORD",
			"SPRING_RABBITMQ_VIRTUAL_HOST",
			"SPRING_RABBITMQ_SSL_KEY_STORE",
			"SPRING_RABBITMQ_SSL_KEY_STORE_PASSWORD",
			"SPRING_RABBITMQ_SSL_TRUST_STORE",
			"SPRING_RABBITMQ_SSL_TRUST_STORE_PASSWORD",
			"SPRING_DATA_REDIS_URL",
			"SPRING_DATA_REDIS_USERNAME",
			"SPRING_DATA_REDIS_PASSWORD",
			"SPRING_REDIS_URL",
			"SPRING_REDIS_USERNAME",
			"SPRING_REDIS_PASSWORD",
			"REDIS_URL",
			"REDIS_USERNAME",
			"REDIS_PASSWORD"
	);

	@Test
	void acceptsDedicatedLeastPrivilegeProductionContract() {
		assertThatCode(() -> new MediaWorkerSafetyValidator(safeEnvironment()).validate())
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsEveryApiOrSharedCredentialNamespaceInNativeProcessEnvironment() {
		assertThat(MediaWorkerSafetyValidator.forbiddenApiEnvironmentNames())
				.containsExactlyInAnyOrderElementsOf(EXPECTED_FORBIDDEN_API_ENVIRONMENT);
		for (String forbidden : EXPECTED_FORBIDDEN_API_ENVIRONMENT) {
			MockEnvironment environment = safeEnvironment()
					.withProperty(forbidden, "must-not-be-here");

			assertThatThrownBy(() -> new MediaWorkerSafetyValidator(environment).validate())
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(forbidden)
					.hasMessageContaining("must not be injected into the native worker");
		}
	}

	@Test
	void allowsHarmlessSharedHostAndPortHints() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("SPRING_RABBITMQ_HOST", "broker.internal")
				.withProperty("SPRING_RABBITMQ_PORT", "5671")
				.withProperty("SPRING_DATA_REDIS_HOST", "redis.internal")
				.withProperty("SPRING_DATA_REDIS_PORT", "6379");

		assertThatCode(() -> new MediaWorkerSafetyValidator(environment).validate())
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsSharedOrOverriddenDatabaseIdentity() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("spring.datasource.username", "api-owner");

		assertThatThrownBy(() -> new MediaWorkerSafetyValidator(environment).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("database identity");
	}

	@Test
	void rejectsWebOrDisabledWorkerOverrides() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("spring.main.web-application-type", "servlet")
				.withProperty("media.transcode.worker.enabled", "false");

		assertThatThrownBy(() -> new MediaWorkerSafetyValidator(environment).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("web-application-type")
				.hasMessageContaining("media.transcode.worker.enabled");
	}

	@Test
	void rejectsNonTlsRabbitOrNonOacProductionDelivery() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("spring.rabbitmq.ssl.enabled", "false")
				.withProperty("cloud.storage.aclPublicReadOnPut", "true")
				.withProperty("cloud.storage.cdnBaseUrl", "");

		assertThatThrownBy(() -> new MediaWorkerSafetyValidator(environment).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("spring.rabbitmq.ssl.enabled must be true")
				.hasMessageContaining("cloud.storage.aclPublicReadOnPut must be false")
				.hasMessageContaining("CDN base URL");
	}

	private static MockEnvironment safeEnvironment() {
		return new MockEnvironment()
				.withProperty("soundconnect.runtime-role", "media-worker")
				.withProperty("spring.main.web-application-type", "none")
				.withProperty("spring.jpa.hibernate.ddl-auto", "validate")
				.withProperty("spring.datasource.hikari.connection-init-sql", "SET TIME ZONE 'UTC'")
				.withProperty("media.transcode.worker.enabled", "true")
				.withProperty("media.image-variants.worker.enabled", "true")
				.withProperty("media.image-variants.backfill.enabled", "true")
				.withProperty("media.transcode.dispatch.enabled", "false")
				.withProperty("media.transcode.worker-threads", "1")
				.withProperty("rabbitmq.media.listenerConcurrency", "1")
				.withProperty("spring.rabbitmq.ssl.enabled", "true")
				.withProperty("spring.rabbitmq.ssl.validate-server-certificate", "true")
				.withProperty("spring.rabbitmq.ssl.verify-hostname", "true")
				.withProperty("cloud.storage.aclPublicReadOnPut", "false")
				.withProperty("soundconnect.media-worker.health-max-age-seconds", "60")
				.withProperty("management.endpoints.web.exposure.exclude", "*")
				.withProperty("management.endpoints.jmx.exposure.exclude", "*")
				.withProperty("management.endpoint.health.show-details", "never")
				.withProperty("spring.datasource.url", "jdbc:postgresql://db/worker")
				.withProperty("soundconnect.media-worker.db-url", "jdbc:postgresql://db/worker")
				.withProperty("spring.datasource.username", "media_worker")
				.withProperty("soundconnect.media-worker.db-username", "media_worker")
				.withProperty("spring.datasource.password", "worker-db-secret")
				.withProperty("soundconnect.media-worker.db-password", "worker-db-secret")
				.withProperty("spring.rabbitmq.username", "media-worker")
				.withProperty("soundconnect.media-worker.rabbitmq-username", "media-worker")
				.withProperty("spring.rabbitmq.password", "worker-rabbit-secret")
				.withProperty("soundconnect.media-worker.rabbitmq-password", "worker-rabbit-secret")
				.withProperty("spring.rabbitmq.virtual-host", "/media-worker")
				.withProperty("soundconnect.media-worker.rabbitmq-virtual-host", "/media-worker")
				.withProperty("cloud.storage.bucket", "public-bucket")
				.withProperty("cloud.storage.privateBucket", "private-bucket")
				.withProperty("cloud.storage.region", "eu-central-1")
				.withProperty("cloud.storage.cdnBaseUrl", "https://cdn.worker.test.invalid")
				.withProperty("cloud.storage.accessKey", "")
				.withProperty("cloud.storage.secretKey", "")
				.withProperty("soundconnect.media-worker.s3-access-key", "")
				.withProperty("soundconnect.media-worker.s3-secret-key", "");
	}
}
