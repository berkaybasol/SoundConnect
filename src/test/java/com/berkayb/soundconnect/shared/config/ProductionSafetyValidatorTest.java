package com.berkayb.soundconnect.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyValidatorTest {

	@Test
	void acceptsSafeEffectiveProductionConfiguration() {
		ProductionSafetyValidator validator = new ProductionSafetyValidator(safeEnvironment());

		assertThatCode(validator::validate).doesNotThrowAnyException();
	}

	@Test
	void rejectsExternalOverrideThatEnablesBootstrap() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("app.data.init.enabled", "true");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.data.init.enabled must be false");
	}

	@Test
	void rejectsContainerLevelForwardedHeaderRewriting() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("server.forward-headers-strategy", "framework");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("server.forward-headers-strategy must be none");
	}

	@Test
	void rejectsDatabaseSessionTimezoneOverride() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("spring.datasource.hikari.connection-init-sql", "SET TIME ZONE 'Europe/Istanbul'");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("spring.datasource.hikari.connection-init-sql must be SET TIME ZONE 'UTC'");
	}

	@Test
	void rejectsFailOpenMediaUploadProtection() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("media.upload-guard.fail-open", "true");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("media.upload-guard.fail-open must be false");
	}

	@Test
	void rejectsPublicReadAclOrMissingCdnInProduction() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("cloud.storage.aclPublicReadOnPut", "true")
				.withProperty("cloud.storage.cdnBaseUrl", "");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("cloud.storage.aclPublicReadOnPut must be false")
				.hasMessageContaining("cdnBaseUrl must be explicitly configured");
	}

	@Test
	void rejectsFfmpegWorkerOnProductionApiNode() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("media.transcode.worker.enabled", "true");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("media.transcode.worker.enabled must be false");
	}

	@Test
	void rejectsNativeImageWorkerOnProductionApiNode() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("media.image-variants.worker.enabled", "true");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("media.image-variants.worker.enabled must be false");
	}

	@Test
	void rejectsReverseCredentialLeakFromWorkerIntoProductionApi() {
		for (String workerCredential : List.of(
				"SOUNDCONNECT_MEDIA_WORKER_POSTGRES_URL",
				"SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME",
				"SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD",
				"SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME",
				"SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD",
				"SOUNDCONNECT_MEDIA_WORKER_S3_ACCESS_KEY",
				"SOUNDCONNECT_MEDIA_WORKER_S3_SECRET_KEY"
		)) {
			MockEnvironment environment = safeEnvironment()
					.withProperty(workerCredential, "worker-only-value");

			assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(workerCredential)
					.hasMessageContaining("must not be injected into production API nodes");
		}
	}

	@Test
	void rejectsDeclaredTempVolumeSmallerThanGlobalReservation() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("transcode.tempVolumeBytes", "2147483648");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("tempVolumeBytes must cover");
	}

	@Test
	void rejectsWorkerProfileOnProductionApiArtifact() {
		MockEnvironment environment = safeEnvironment();
		environment.setActiveProfiles("prod", "transcode-worker");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("isolated media-worker artifact");
	}

	@Test
	void rejectsNativeImageProfileOnProductionApiArtifact() {
		MockEnvironment environment = safeEnvironment();
		environment.setActiveProfiles("prod", "image-worker");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("native worker profiles")
				.hasMessageContaining("isolated media-worker artifact");
	}

	@Test
	void rejectsDisabledDurableUploadVerificationRecovery() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("media.upload-verification.enabled", "false");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("media.upload-verification.enabled must be true");
	}

	@Test
	void rejectsMissingSafetyInvariant() {
		MockEnvironment environment = new MockEnvironment();

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("spring.jpa.hibernate.ddl-auto must be validate")
				.hasMessageContaining("springdoc.swagger-ui.enabled must be false");
	}

	@Test
	void rejectsUnboundedRedisTimeouts() {
		MockEnvironment environment = safeEnvironment()
				.withProperty("spring.data.redis.connect-timeout", "30s");

		assertThatThrownBy(new ProductionSafetyValidator(environment)::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("spring.data.redis.connect-timeout must be greater than zero and at most PT2S");
	}

	private MockEnvironment safeEnvironment() {
		return new MockEnvironment()
				.withProperty("spring.jpa.hibernate.ddl-auto", "validate")
				.withProperty("spring.datasource.hikari.connection-init-sql", "SET TIME ZONE 'UTC'")
				.withProperty("server.forward-headers-strategy", "none")
				.withProperty("spring.data.redis.connect-timeout", "1s")
				.withProperty("spring.data.redis.timeout", "2s")
				.withProperty("app.security.auth-rate-limit.enabled", "true")
				.withProperty("app.listener-profile.visibility-rate-limit.enabled", "true")
				.withProperty("app.table-group.rate-limit.enabled", "true")
				.withProperty("app.websocket.broker-relay.enabled", "true")
				.withProperty("app.data.init.enabled", "false")
				.withProperty("app.data.init.owner.enabled", "false")
				.withProperty("app.location.seed.enabled", "false")
				.withProperty("springdoc.api-docs.enabled", "false")
				.withProperty("springdoc.swagger-ui.enabled", "false")
				.withProperty("server.error.include-message", "never")
				.withProperty("server.error.include-binding-errors", "never")
				.withProperty("server.error.include-stacktrace", "never")
				.withProperty("management.endpoints.web.exposure.include", "health")
				.withProperty("management.endpoint.health.show-details", "never")
				.withProperty("management.endpoint.health.probes.enabled", "true")
				.withProperty("media.upload-guard.enabled", "true")
				.withProperty("media.upload-guard.fail-open", "false")
				.withProperty("media.upload-verification.enabled", "true")
				.withProperty("media.upload-cleanup.enabled", "true")
				.withProperty("media.deletion.enabled", "true")
				.withProperty("media.transcode.dispatch.enabled", "true")
				.withProperty("media.transcode.worker.enabled", "false")
				.withProperty("media.image-variants.worker.enabled", "false")
				.withProperty("media.transcode.worker-threads", "1")
				.withProperty("rabbitmq.media.listenerConcurrency", "1")
				.withProperty("transcode.maxTempWorkBytes", "6442450944")
				.withProperty("transcode.globalTempBudgetBytes", "6442450944")
				.withProperty("transcode.tempVolumeBytes", "6442450944")
				.withProperty("media.upload-cleanup.protected-mutable-recovery-enabled", "true")
				.withProperty("media.public-promotion-recovery.enabled", "true")
				.withProperty("cloud.storage.aclPublicReadOnPut", "false")
				.withProperty("cloud.storage.cdnBaseUrl", "https://cdn.api.test.invalid")
				.withProperty(
						"management.endpoint.health.group.readiness.include",
						"readinessState,db,redis,rabbit,webSocketBrokerRelayHealth,tableGroupNotificationOutboxHealth"
				);
	}
}
