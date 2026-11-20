package com.berkayb.soundconnectworker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Real Boot config-data ordering, without creating beans, opening sockets or importing a developer .env. */
class MediaWorkerProfilePrecedenceTest {

    @TempDir Path configuration;

    @Test
    void additionalWorkerProfileCannotBeOverriddenByApiProductionConfiguration() throws IOException {
        StandardEnvironment environment = load("prod,media-worker", List.of("media-worker"), workerInput());

        // SpringApplication.setAdditionalProfiles puts the additional profile first.
        assertThat(environment.getActiveProfiles()).containsExactly("media-worker", "prod");
        assertThat(environment.getProperty("spring.rabbitmq.username")).isEqualTo("fixture-worker");
        assertThat(environment.getProperty("spring.rabbitmq.password")).isEqualTo("fixture-worker-password");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("fixture-worker-db");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("fixture-worker-db-password");
        assertThat(environment.getProperty("cloud.storage.bucket")).isEqualTo("fixture-worker-public");
        assertThat(environment.getProperty("cloud.storage.privateBucket")).isEqualTo("fixture-worker-private");
        assertThat(environment.getProperty("cloud.storage.region")).isEqualTo("eu-central-1");
        assertThat(environment.getProperty("cloud.storage.cdnBaseUrl")).isEqualTo("https://fixture.invalid");
        assertThat(environment.getProperty("media.transcode.worker.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("media.image-variants.worker.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.endpoint.health.enabled")).isEqualTo("false");

        RabbitProperties rabbit = Binder.get(environment).bind("spring.rabbitmq", Bindable.of(RabbitProperties.class)).get();
        assertThat(rabbit.getUsername()).isEqualTo("fixture-worker");
        assertThat(rabbit.getSsl().getEnabled()).isTrue();
        assertThat(rabbit.getSsl().getTrustStore()).isEqualTo("file:/synthetic/worker.p12");
        assertThat(environment.getProperty("spring.rabbitmq.ssl.validate-server-certificate")).isEqualTo("true");
        assertThat(environment.getProperty("spring.rabbitmq.ssl.verify-hostname")).isEqualTo("true");
        assertThatCode(() -> new MediaWorkerSafetyValidator(environment).validate()).doesNotThrowAnyException();
        assertThat(environment.containsProperty("SPRING_RABBITMQ_USERNAME")).isFalse();
    }

    @Test
    void additionalWorkerProfileAlsoIsolatesProductionOnlyLaunch() throws IOException {
        StandardEnvironment environment = load("prod", List.of("media-worker"), workerInput());
        assertThat(environment.getActiveProfiles()).containsExactly("media-worker", "prod");
        assertThat(environment.getProperty("spring.rabbitmq.username")).isEqualTo("fixture-worker");
        assertThatCode(() -> new MediaWorkerSafetyValidator(environment).validate()).doesNotThrowAnyException();
    }

    @Test
    void apiProductionConfigurationRetainsItsOwnSafetyDefaults() throws IOException {
        StandardEnvironment environment = load("prod", List.of(), Map.of(
                "SPRING_RABBITMQ_HOST", "fixture-broker.invalid",
                "SPRING_RABBITMQ_USERNAME", "fixture-api",
                "SPRING_RABBITMQ_PASSWORD", "fixture-api-password",
                "S3_BUCKET", "fixture-api-public"));
        assertThat(environment.getActiveProfiles()).containsExactly("prod");
        assertThat(environment.getProperty("spring.rabbitmq.username")).isEqualTo("fixture-api");
        assertThat(environment.getProperty("cloud.storage.bucket")).isEqualTo("fixture-api-public");
        assertThat(environment.getProperty("media.transcode.worker.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("app.data.init.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("app.data.init.owner.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("app.websocket.broker-relay.enabled")).isEqualTo("true");
    }

    private StandardEnvironment load(String profiles, List<String> additional, Map<String, Object> input) throws IOException {
        // Copy only the two actual profile documents. Never load application.yml's optional file import.
        for (String name : List.of("application-prod.yml", "application-media-worker.yml")) {
            try (var source = new ClassPathResource(name).getInputStream()) {
                Files.copy(source, configuration.resolve(name));
            }
        }
        Files.writeString(configuration.resolve("application.yml"), "spring.application.name: isolated-config-test\n");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        Map<String, Object> properties = new LinkedHashMap<>(input);
        properties.put("spring.config.location", configuration.toUri().toString());
        properties.put("spring.profiles.active", profiles);
        environment.getPropertySources().addFirst(new MapPropertySource("synthetic-only", properties));
        ConfigDataEnvironmentPostProcessor.applyTo(environment, null, null, additional);
        return environment;
    }

    private Map<String, Object> workerInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("SOUNDCONNECT_MEDIA_WORKER_POSTGRES_URL", "jdbc:postgresql://fixture.invalid/worker");
        input.put("SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME", "fixture-worker-db");
        input.put("SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD", "fixture-worker-db-password");
        input.put("SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_HOST", "fixture-broker.invalid");
        input.put("SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME", "fixture-worker");
        input.put("SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD", "fixture-worker-password");
        input.put("SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_SSL_ENABLED", "true");
        input.put("SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_SSL_TRUST_STORE", "file:/synthetic/worker.p12");
        input.put("SOUNDCONNECT_MEDIA_WORKER_S3_PUBLIC_BUCKET", "fixture-worker-public");
        input.put("SOUNDCONNECT_MEDIA_WORKER_S3_PRIVATE_BUCKET", "fixture-worker-private");
        input.put("SOUNDCONNECT_MEDIA_WORKER_AWS_REGION", "eu-central-1");
        input.put("SOUNDCONNECT_MEDIA_WORKER_CDN_BASE_URL", "https://fixture.invalid");
        return input;
    }
}
