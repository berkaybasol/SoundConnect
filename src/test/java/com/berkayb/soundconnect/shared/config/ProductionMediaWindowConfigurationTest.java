package com.berkayb.soundconnect.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads the real prod YAML, not a hand-maintained copy of the validator settings. */
class ProductionMediaWindowConfigurationTest {
    private static final String MUTABLE = "SOUNDCONNECT_MEDIA_PROTECTED_MUTABLE_RECOVERY_ENABLED";
    private static final String PROMOTION = "SOUNDCONNECT_MEDIA_PUBLIC_PROMOTION_RECOVERY_ENABLED";

    private ApplicationContextRunner context(Map<String, Object> overrides) {
        return new ApplicationContextRunner().withInitializer(context -> {
            var environment = context.getEnvironment();
            // Never import the developer's system environment, local secrets, or test profile.
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            var fixture = new LinkedHashMap<String, Object>();
            fixture.put("SOUNDCONNECT_CONFIG_IMPORT", "");
            fixture.put("S3_CDN_BASE_URL", "https://fixture.invalid");
            fixture.put("CLOUD_STORAGE_VERSION_PURGE_ENABLED", "true");
            fixture.putAll(overrides);
            environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("owned-fixture", fixture));
            environment.getPropertySources().addFirst(new MapPropertySource("owned-config", Map.of(
                    "spring.config.location", "classpath:/application.yml", "spring.profiles.active", "prod")));
            new ConfigDataApplicationContextInitializer().initialize(context);
        }).withUserConfiguration(ProductionSafetyValidator.class);
    }

    @Test void normalVersionPurgeCandidateStartsWithActualProdDefaults() {
        context(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProductionSafetyValidator.class);
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("prod");
            assertThat(context.getEnvironment().getProperty("cloud.storage.version-purge.enabled")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("media.upload-cleanup.protected-mutable-recovery-enabled")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("media.public-promotion-recovery.enabled")).isEqualTo("true");
        });
    }

    @Test void failedLegacyWindowIsRejectedByRealProdConfiguration() {
        context(Map.of(MUTABLE, "false", PROMOTION, "false")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Unsafe production configuration: media.upload-cleanup.protected-mutable-recovery-enabled must be true; "
                    + "media.public-promotion-recovery.enabled must be true");
        });
    }

    @Test void eitherDisabledRecoveryInvariantStillPreventsStartup() {
        for (String key : new String[]{MUTABLE, PROMOTION}) {
            context(Map.of(key, "false")).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
            });
        }
    }
}
