package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSource;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;

@Configuration
@EnableConfigurationProperties(MusicianFeedProperties.class)
public class MusicianFeedConfiguration {

    @Bean
    @ConditionalOnMissingBean(MusicianFeedPersonalizationSource.class)
    MusicianFeedPersonalizationSource emptyMusicianFeedPersonalizationSource() {
        return (userId, profileId) -> MusicianFeedPersonalizationSnapshot.empty();
    }

    @Bean
    ApplicationRunner musicianFeedProductionSafetyValidator(
            MusicianFeedProperties properties,
            Environment environment
    ) {
        return ignored -> {
            boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
            if (!properties.isEnabled() && !properties.isCleanupEnabled()) return;
            validateOperationalBounds(properties);
            if (properties.isEnabled() && !properties.isCleanupEnabled()) {
                throw new IllegalStateException("Enabled musician feed requires retention cleanup");
            }
            if (!production || !properties.isEnabled()) return;
            String secret = properties.getCursorSecret();
            if (secret == null || secret.isBlank()
                    || secret.equals(MusicianFeedProperties.DEVELOPMENT_CURSOR_SECRET)
                    || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                        "Enabled production musician feed requires a dedicated cursor HMAC secret of at least 32 bytes");
            }
            String deliverySecret = properties.getDeliverySecret();
            if (deliverySecret == null || deliverySecret.isBlank()
                    || deliverySecret.equals(MusicianFeedProperties.DEVELOPMENT_DELIVERY_SECRET)
                    || deliverySecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                        "Enabled production musician feed requires a dedicated delivery HMAC secret of at least 32 bytes");
            }
            if (properties.isMockSponsorsEnabled()) {
                throw new IllegalStateException("Mock musician-feed sponsors must stay disabled in production");
            }
        };
    }

    private void validateOperationalBounds(MusicianFeedProperties properties) {
        if (properties.getCursorTtl() == null || properties.getCursorTtl().isNegative()
                || properties.getCursorTtl().isZero()
                || properties.getDeliveryTtl() == null || properties.getDeliveryTtl().isNegative()
                || properties.getDeliveryTtl().isZero()
                || properties.getDeliveryTtl().compareTo(properties.getCursorTtl()) < 0
                || properties.getDeliveryRetention() == null
                || properties.getDeliveryRetention().compareTo(properties.getDeliveryTtl()) < 0
                || properties.getTelemetryRetention() == null
                || properties.getTelemetryRetention().isNegative()
                || properties.getTelemetryRetention().isZero()
                || properties.getDeliveryRetention().compareTo(properties.getTelemetryRetention()) < 0
                || properties.getTelemetryClockSkew() == null || properties.getTelemetryClockSkew().isNegative()
                || properties.getProviderDeadline() == null || properties.getProviderDeadline().isNegative()
                || properties.getProviderDeadline().isZero()
                || properties.getCleanupTimeBudget() == null || properties.getCleanupTimeBudget().isNegative()
                || properties.getCleanupTimeBudget().isZero()
                || properties.getProviderParallelism() < 1 || properties.getProviderParallelism() > 16
                || properties.getProviderQueueCapacity() < properties.getProviderParallelism()
                || properties.getProviderQueueCapacity() > 256
                || properties.getSponsorCampaignDailyCap() < 1
                || properties.getCleanupBatchSize() < 1 || properties.getCleanupBatchSize() > 10_000
                || properties.getCleanupMaxBatches() < 1 || properties.getCleanupMaxBatches() > 100
                || properties.getDefaultPageSize() < 1
                || properties.getMaxPageSize() < properties.getDefaultPageSize()
                || properties.getMaxPageSize() > 100
                || properties.getProviderLimit() < properties.getMaxPageSize()
                || properties.getMaxSessionDeliveries() < properties.getMaxPageSize()
                || properties.getMaxSessionDeliveries() > 10_000) {
            throw new IllegalStateException("Invalid musician-feed operational bounds");
        }
    }

    @Bean(name = "musicianFeedProviderExecutor", destroyMethod = "shutdownNow")
    ExecutorService musicianFeedProviderExecutor(MusicianFeedProperties properties) {
        int parallelism = Math.max(1, Math.min(properties.getProviderParallelism(), 16));
        int queueCapacity = Math.max(parallelism, Math.min(properties.getProviderQueueCapacity(), 256));
        ThreadFactory factory = new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "musician-feed-provider-" + sequence.incrementAndGet());
                thread.setDaemon(false);
                return thread;
            }
        };
        return new ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }
}
