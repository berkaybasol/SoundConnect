package com.berkayb.soundconnect.modules.feed.musician.abuse;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MusicianFeedRateLimitProperties.class)
public class MusicianFeedRateLimitConfiguration {

    @Bean
    ApplicationRunner musicianFeedRateLimitProductionSafetyValidator(
            MusicianFeedRateLimitProperties properties,
            Environment environment
    ) {
        return ignored -> validateProductionSafety(properties, environment);
    }

    static void validateProductionSafety(MusicianFeedRateLimitProperties properties,
                                         Environment environment) {
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        boolean feedEnabled = environment.getProperty("app.feed.musician.enabled", Boolean.class, true);
        if (production && feedEnabled && !properties.isEnabled()) {
            throw new IllegalStateException(
                    "Enabled production musician feed requires the Redis rate limiter");
        }
    }
}
