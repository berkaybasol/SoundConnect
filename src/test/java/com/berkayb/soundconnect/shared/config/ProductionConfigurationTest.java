package com.berkayb.soundconnect.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionConfigurationTest {

	@Test
	void baseRuntimeConfigurationUsesUtcForPersistenceAndSerialization() throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
				"application",
				new ClassPathResource("application.yml")
		);

		assertThat(property(sources, "spring.jackson.time-zone")).isEqualTo("UTC");
		assertThat(property(sources, "spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
		assertThat(property(sources, "soundconnect.spotify.o-embed-base-url"))
				.isEqualTo("https://open.spotify.com");
		assertThat(property(sources, "soundconnect.spotify.http.o-embed-max-connections"))
				.isEqualTo("${SPOTIFY_OEMBED_MAX_CONNECTIONS:32}");
		assertThat(property(sources, "soundconnect.spotify.http.o-embed-pending-acquire-max-count"))
				.isEqualTo("${SPOTIFY_OEMBED_PENDING_ACQUIRE_MAX_COUNT:64}");
		assertThat(property(sources, "soundconnect.spotify.http.o-embed-pending-acquire-timeout-ms"))
				.isEqualTo("${SPOTIFY_OEMBED_PENDING_ACQUIRE_TIMEOUT_MS:750}");
		assertThat(property(sources, "soundconnect.spotify.http.o-embed-max-response-bytes"))
				.isEqualTo("${SPOTIFY_OEMBED_MAX_RESPONSE_BYTES:65536}");
	}

	@Test
	void productionProfileKeepsSchemaDocumentationAndBootstrapSafe() throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
				"application-prod",
				new ClassPathResource("application-prod.yml")
		);

		assertThat(property(sources, "spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
		assertThat(property(sources, "spring.jpa.show-sql")).isEqualTo(false);
		assertThat(property(sources, "springdoc.api-docs.enabled")).isEqualTo(false);
		assertThat(property(sources, "springdoc.swagger-ui.enabled")).isEqualTo(false);
		assertThat(property(sources, "app.data.init.enabled")).isEqualTo(false);
		assertThat(property(sources, "app.data.init.owner.enabled")).isEqualTo(false);
		assertThat(property(sources, "app.location.seed.enabled")).isEqualTo(false);
		assertThat(property(sources, "media.transcode.worker.enabled")).isEqualTo(false);
		assertThat(property(sources, "media.image-variants.worker.enabled")).isEqualTo(false);
		assertThat(property(sources, "logging.level.com.berkayb.soundconnect")).isEqualTo("INFO");
		assertThat(property(sources, "server.error.include-message")).isEqualTo("never");
		assertThat(property(sources, "server.error.include-binding-errors")).isEqualTo("never");
		assertThat(property(sources, "server.error.include-stacktrace")).isEqualTo("never");
		assertThat(property(sources, "management.endpoints.web.exposure.include")).isEqualTo("health");
		assertThat(property(sources, "management.endpoint.health.show-details")).isEqualTo("never");
		assertThat(property(sources, "management.endpoint.health.probes.enabled")).isEqualTo(true);
		assertThat(property(sources, "app.websocket.broker-relay.enabled")).isEqualTo(true);
		assertThat(property(sources, "app.listener-profile.visibility-rate-limit.enabled")).isEqualTo(true);
		assertThat(property(sources, "app.listener-profile.playlist-rate-limit.enabled")).isEqualTo(true);
		assertThat(property(sources, "app.musician-profile.calendar-rate-limit.enabled")).isEqualTo(true);
		assertThat(property(sources, "app.table-group.rate-limit.enabled")).isEqualTo(true);
		assertThat(property(sources, "management.endpoint.health.group.readiness.include"))
				.isEqualTo("readinessState,db,redis,rabbit,webSocketBrokerRelayHealth,tableGroupNotificationOutboxHealth,eventPerformerNotificationOutboxHealth");
	}

	private Object property(List<PropertySource<?>> sources, String key) {
		return sources.stream()
				.map(source -> source.getProperty(key))
				.filter(value -> value != null)
				.findFirst()
				.orElse(null);
	}
}
