package com.berkayb.soundconnect.modules.feed.musician.abuse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MusicianFeedRateLimitConfigurationTest {
    @Test
    void productionStartupRejectsEnabledFeedWithLimiterDisabled() {
        assertThatThrownBy(() -> MusicianFeedRateLimitConfiguration.validateProductionSafety(
                new MusicianFeedRateLimitProperties(), productionEnvironment(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the Redis rate limiter");
    }

    @Test
    void productionMayDisableLimiterOnlyWhenFeedServingIsDisabled() {
        MusicianFeedRateLimitConfiguration.validateProductionSafety(
                new MusicianFeedRateLimitProperties(), productionEnvironment(false));
    }

    @Test
    void localDevelopmentCanServeWithoutRedisLimiter() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("app.feed.musician.enabled", "true");

        MusicianFeedRateLimitConfiguration.validateProductionSafety(
                new MusicianFeedRateLimitProperties(), environment);
    }

    @Test
    void productionProfileDefaultsLimiterOn() throws IOException {
        var sources = new YamlPropertySourceLoader().load(
                "application-prod", new ClassPathResource("application-prod.yml"));

        assertThat(sources)
                .anySatisfy(source -> assertThat(source.getProperty(
                        "app.feed.musician.rate-limit.enabled"))
                        .isEqualTo("${SOUNDCONNECT_MUSICIAN_FEED_RATE_LIMIT_ENABLED:true}"));
    }

    private MockEnvironment productionEnvironment(boolean feedEnabled) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("app.feed.musician.enabled", Boolean.toString(feedEnabled));
        return environment;
    }
}
