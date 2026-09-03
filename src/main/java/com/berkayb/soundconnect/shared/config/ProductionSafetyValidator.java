package com.berkayb.soundconnect.shared.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fails startup when an external property source overrides a production safety invariant.
 * Environment variables intentionally outrank profile YAML, so YAML defaults alone cannot
 * guarantee that schema mutation, bootstrap jobs, or API documentation remain disabled.
 */
@Component
@Profile("prod")
public class ProductionSafetyValidator {
	private static final List<String> FORBIDDEN_WORKER_CREDENTIALS = List.of(
			"SOUNDCONNECT_MEDIA_WORKER_POSTGRES_URL",
			"SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME",
			"SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD",
			"SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME",
			"SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD",
			"SOUNDCONNECT_MEDIA_WORKER_S3_ACCESS_KEY",
			"SOUNDCONNECT_MEDIA_WORKER_S3_SECRET_KEY"
	);

	private static final List<RequiredSetting> REQUIRED_SETTINGS = List.of(
			new RequiredSetting("spring.jpa.hibernate.ddl-auto", "validate"),
			new RequiredSetting("spring.datasource.hikari.connection-init-sql", "SET TIME ZONE 'UTC'"),
			new RequiredSetting("server.forward-headers-strategy", "none"),
			new RequiredSetting("app.security.auth-rate-limit.enabled", "true"),
			new RequiredSetting("app.listener-profile.visibility-rate-limit.enabled", "true"),
			new RequiredSetting("app.table-group.rate-limit.enabled", "true"),
			new RequiredSetting("app.websocket.broker-relay.enabled", "true"),
			new RequiredSetting("app.data.init.enabled", "false"),
			new RequiredSetting("app.data.init.owner.enabled", "false"),
			new RequiredSetting("app.location.seed.enabled", "false"),
			new RequiredSetting("springdoc.api-docs.enabled", "false"),
			new RequiredSetting("springdoc.swagger-ui.enabled", "false"),
			new RequiredSetting("server.error.include-message", "never"),
			new RequiredSetting("server.error.include-binding-errors", "never"),
			new RequiredSetting("server.error.include-stacktrace", "never"),
			new RequiredSetting("management.endpoints.web.exposure.include", "health"),
			new RequiredSetting("management.endpoint.health.show-details", "never"),
			new RequiredSetting("management.endpoint.health.probes.enabled", "true"),
			new RequiredSetting("media.upload-guard.enabled", "true"),
			new RequiredSetting("media.upload-guard.fail-open", "false"),
			new RequiredSetting("media.upload-verification.enabled", "true"),
			new RequiredSetting("media.upload-cleanup.enabled", "true"),
			new RequiredSetting("media.deletion.enabled", "true"),
			new RequiredSetting("media.transcode.dispatch.enabled", "true"),
			new RequiredSetting("media.transcode.worker-threads", "1"),
			new RequiredSetting("rabbitmq.media.listenerConcurrency", "1"),
			new RequiredSetting("media.upload-cleanup.protected-mutable-recovery-enabled", "true"),
			new RequiredSetting("media.public-promotion-recovery.enabled", "true"),
			new RequiredSetting("cloud.storage.aclPublicReadOnPut", "false"),
			new RequiredSetting(
					"management.endpoint.health.group.readiness.include",
					"readinessState,db,redis,rabbit,webSocketBrokerRelayHealth,tableGroupNotificationOutboxHealth"
			)
	);

	private final Environment environment;

	public ProductionSafetyValidator(Environment environment) {
		this.environment = environment;
	}

	@PostConstruct
	void validate() {
		List<String> violations = new ArrayList<>();
		for (RequiredSetting setting : REQUIRED_SETTINGS) {
			String actual = environment.getProperty(setting.key());
			if (actual == null || !setting.requiredValue().equalsIgnoreCase(actual.trim())) {
				violations.add(setting.key() + " must be " + setting.requiredValue());
			}
		}

		boolean transcodeWorkerProfile = environment.acceptsProfiles(
				Profiles.of("transcode-worker"));
		if (transcodeWorkerProfile) {
			violations.add(
					"transcode-worker profile is invalid on the production API artifact; deploy the isolated media-worker artifact");
		} else {
			String workerEnabled = environment.getProperty("media.transcode.worker.enabled");
			if (!"false".equalsIgnoreCase(workerEnabled != null ? workerEnabled.trim() : null)) {
				violations.add("media.transcode.worker.enabled must be false on production API nodes");
			}
		}

		String imageWorkerEnabled = environment.getProperty("media.image-variants.worker.enabled");
		if (!"false".equalsIgnoreCase(
				imageWorkerEnabled != null ? imageWorkerEnabled.trim() : null)) {
			violations.add(
					"media.image-variants.worker.enabled must be false on production API nodes");
		}
		if (environment.acceptsProfiles(Profiles.of("image-worker", "media-worker"))) {
			violations.add(
					"native worker profiles are invalid on the production API artifact; deploy the isolated media-worker artifact");
		}

		for (String workerCredential : FORBIDDEN_WORKER_CREDENTIALS) {
			String injected = environment.getProperty(workerCredential);
			if (injected != null && !injected.isBlank()) {
				violations.add(workerCredential + " must not be injected into production API nodes");
			}
		}
		if (!StringUtils.hasText(environment.getProperty("cloud.storage.cdnBaseUrl"))) {
			violations.add("cloud.storage.cdnBaseUrl must be explicitly configured for production OAC delivery");
		}

		validateTempCapacity(violations);
		validateBoundedDuration("spring.data.redis.connect-timeout", Duration.ofSeconds(2), violations);
		validateBoundedDuration("spring.data.redis.timeout", Duration.ofSeconds(3), violations);

		if (!violations.isEmpty()) {
			throw new IllegalStateException(
					"Unsafe production configuration: " + String.join("; ", violations)
			);
		}
	}

	private void validateTempCapacity(List<String> violations) {
		Long perJob = readPositiveLong("transcode.maxTempWorkBytes", violations);
		Long global = readPositiveLong("transcode.globalTempBudgetBytes", violations);
		Long volume = readPositiveLong("transcode.tempVolumeBytes", violations);
		if (perJob == null || global == null || volume == null) return;
		if (global < perJob) {
			violations.add("transcode.globalTempBudgetBytes must cover transcode.maxTempWorkBytes");
		}
		if (volume < global) {
			violations.add("transcode.tempVolumeBytes must cover the global transcode reservation");
		}
	}

	private Long readPositiveLong(String key, List<String> violations) {
		String raw = environment.getProperty(key);
		try {
			long parsed = Long.parseLong(raw != null ? raw.trim() : "");
			if (parsed <= 0) throw new NumberFormatException("non-positive");
			return parsed;
		} catch (NumberFormatException invalid) {
			violations.add(key + " must be a positive byte count");
			return null;
		}
	}

	private void validateBoundedDuration(String key, Duration maximum, List<String> violations) {
		String raw = environment.getProperty(key);
		try {
			Duration parsed = DurationStyle.detectAndParse(raw != null ? raw.trim() : "");
			if (parsed.isZero() || parsed.isNegative() || parsed.compareTo(maximum) > 0) {
				throw new IllegalArgumentException("duration outside safe range");
			}
		} catch (RuntimeException invalid) {
			violations.add(key + " must be greater than zero and at most " + maximum);
		}
	}

	private record RequiredSetting(String key, String requiredValue) {
	}
}
