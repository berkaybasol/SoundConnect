package com.berkayb.soundconnectworker;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fail-closed production contract for the dedicated native worker artifact.
 * The monolith has a separate validator which continues to reject worker
 * profiles, so activating a profile on the wrong JAR cannot bypass isolation.
 */
@Component
@Profile("prod & media-worker")
public class MediaWorkerSafetyValidator {

	private static final List<RequiredSetting> REQUIRED_SETTINGS = List.of(
			new RequiredSetting("soundconnect.runtime-role", "media-worker"),
			new RequiredSetting("spring.main.web-application-type", "none"),
			new RequiredSetting("spring.jpa.hibernate.ddl-auto", "validate"),
			new RequiredSetting("spring.datasource.hikari.connection-init-sql", "SET TIME ZONE 'UTC'"),
			new RequiredSetting("media.transcode.worker.enabled", "true"),
			new RequiredSetting("media.image-variants.worker.enabled", "true"),
			new RequiredSetting("media.image-variants.backfill.enabled", "true"),
			new RequiredSetting("media.transcode.dispatch.enabled", "false"),
			new RequiredSetting("media.transcode.worker-threads", "1"),
			new RequiredSetting("rabbitmq.media.listenerConcurrency", "1"),
			new RequiredSetting("spring.rabbitmq.ssl.enabled", "true"),
			new RequiredSetting("spring.rabbitmq.ssl.validate-server-certificate", "true"),
			new RequiredSetting("spring.rabbitmq.ssl.verify-hostname", "true"),
			new RequiredSetting("cloud.storage.aclPublicReadOnPut", "false"),
			new RequiredSetting("soundconnect.media-worker.health-max-age-seconds", "60"),
			new RequiredSetting("management.endpoints.web.exposure.exclude", "*"),
			new RequiredSetting("management.endpoints.jmx.exposure.exclude", "*"),
			new RequiredSetting("management.endpoint.health.show-details", "never")
	);

	private static final List<String> FORBIDDEN_API_ENVIRONMENT = List.of(
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

	private final Environment environment;

	public MediaWorkerSafetyValidator(Environment environment) {
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

		requireNamespacedIdentity(
				violations,
				"spring.datasource.url",
				"soundconnect.media-worker.db-url",
				"database URL");
		requireNamespacedIdentity(
				violations,
				"spring.datasource.username",
				"soundconnect.media-worker.db-username",
				"database");
		requireNamespacedIdentity(
				violations,
				"spring.datasource.password",
				"soundconnect.media-worker.db-password",
				"database password");
		requireNamespacedIdentity(
				violations,
				"spring.rabbitmq.username",
				"soundconnect.media-worker.rabbitmq-username",
				"RabbitMQ");
		requireNamespacedIdentity(
				violations,
				"spring.rabbitmq.password",
				"soundconnect.media-worker.rabbitmq-password",
				"RabbitMQ password");
		requireNamespacedIdentity(
				violations,
				"spring.rabbitmq.virtual-host",
				"soundconnect.media-worker.rabbitmq-virtual-host",
				"RabbitMQ virtual host");

		String publicBucket = environment.getProperty("cloud.storage.bucket");
		String privateBucket = environment.getProperty("cloud.storage.privateBucket");
		if (!StringUtils.hasText(publicBucket) || !StringUtils.hasText(privateBucket)) {
			violations.add("both worker S3 buckets must be configured");
		} else if (publicBucket.trim().equals(privateBucket.trim())) {
			violations.add("worker public and private S3 buckets must be different");
		}
		if (!StringUtils.hasText(environment.getProperty("cloud.storage.region"))) {
			violations.add("worker S3 region must be configured");
		}
		if (!StringUtils.hasText(environment.getProperty("cloud.storage.cdnBaseUrl"))) {
			violations.add("production worker CDN base URL must be explicitly configured");
		}

		validateWorkerStorageCredentials(violations);
		for (String forbidden : FORBIDDEN_API_ENVIRONMENT) {
			if (hasNonBlankRawProperty(forbidden)) {
				violations.add(forbidden + " must not be injected into the native worker");
			}
		}

		if (!violations.isEmpty()) {
			throw new IllegalStateException(
					"Unsafe isolated media-worker configuration: " + String.join("; ", violations));
		}
	}

	static List<String> forbiddenApiEnvironmentNames() {
		return FORBIDDEN_API_ENVIRONMENT;
	}

	private boolean hasNonBlankRawProperty(String propertyName) {
		if (environment instanceof ConfigurableEnvironment configurable) {
			for (PropertySource<?> propertySource : configurable.getPropertySources()) {
				Object source = propertySource.getSource();
				if (source instanceof Map<?, ?> values) {
					Object exactValue = values.get(propertyName);
					if (exactValue != null && StringUtils.hasText(exactValue.toString())) {
						return true;
					}
				}
			}
			return false;
		}
		return StringUtils.hasText(environment.getProperty(propertyName));
	}

	private void requireNamespacedIdentity(
			List<String> violations,
			String effectiveKey,
			String workerKey,
			String capability
	) {
		String effective = environment.getProperty(effectiveKey);
		String worker = environment.getProperty(workerKey);
		if (!StringUtils.hasText(effective)
				|| !StringUtils.hasText(worker)
				|| !effective.trim().equals(worker.trim())) {
			violations.add(capability + " identity must come from the media-worker namespace");
		}
	}

	private void validateWorkerStorageCredentials(List<String> violations) {
		String access = empty(environment.getProperty("cloud.storage.accessKey"));
		String secret = empty(environment.getProperty("cloud.storage.secretKey"));
		String workerAccess = empty(environment.getProperty("soundconnect.media-worker.s3-access-key"));
		String workerSecret = empty(environment.getProperty("soundconnect.media-worker.s3-secret-key"));
		if (!Objects.equals(access, workerAccess) || !Objects.equals(secret, workerSecret)) {
			violations.add("static S3 credentials must come from the media-worker namespace");
		}
		if (StringUtils.hasText(access) != StringUtils.hasText(secret)) {
			violations.add("both worker S3 static credential fields must be set together");
		}
	}

	private static String empty(String value) {
		return value == null ? "" : value.trim();
	}

	private record RequiredSetting(String key, String requiredValue) {
	}
}
