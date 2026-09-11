package com.berkayb.soundconnect.modules.feed.musician.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.feed.musician")
public class MusicianFeedProperties {
    public static final String DEVELOPMENT_CURSOR_SECRET =
            "soundconnect-local-musician-feed-cursor-secret-change-me";
    public static final String DEVELOPMENT_DELIVERY_SECRET =
            "soundconnect-local-musician-feed-delivery-secret-change-me";

    private boolean enabled = true;
    private String cursorSecret = DEVELOPMENT_CURSOR_SECRET;
    private Duration cursorTtl = Duration.ofHours(24);
    private String deliverySecret = DEVELOPMENT_DELIVERY_SECRET;
    private Duration deliveryTtl = Duration.ofHours(24);
    private Duration deliveryRetention = Duration.ofDays(90);
    private Duration telemetryRetention = Duration.ofDays(90);
    private Duration telemetryClockSkew = Duration.ofMinutes(10);
    private Duration providerDeadline = Duration.ofSeconds(4);
    private int providerParallelism = 6;
    private int providerQueueCapacity = 24;
    private int sponsorCampaignDailyCap = 3;
    private int cleanupBatchSize = 1_000;
    private int cleanupMaxBatches = 20;
    private Duration cleanupTimeBudget = Duration.ofSeconds(5);
    private boolean cleanupEnabled = true;
    private int maxSessionDeliveries = 2_000;
    private int defaultPageSize = 20;
    private int maxPageSize = 50;
    private int providerLimit = 160;
    private boolean mockSponsorsEnabled = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCursorSecret() { return cursorSecret; }
    public void setCursorSecret(String cursorSecret) { this.cursorSecret = cursorSecret; }
    public Duration getCursorTtl() { return cursorTtl; }
    public void setCursorTtl(Duration cursorTtl) { this.cursorTtl = cursorTtl; }
    public String getDeliverySecret() { return deliverySecret; }
    public void setDeliverySecret(String deliverySecret) { this.deliverySecret = deliverySecret; }
    public Duration getDeliveryTtl() { return deliveryTtl; }
    public void setDeliveryTtl(Duration deliveryTtl) { this.deliveryTtl = deliveryTtl; }
    public Duration getDeliveryRetention() { return deliveryRetention; }
    public void setDeliveryRetention(Duration deliveryRetention) { this.deliveryRetention = deliveryRetention; }
    public Duration getTelemetryRetention() { return telemetryRetention; }
    public void setTelemetryRetention(Duration telemetryRetention) { this.telemetryRetention = telemetryRetention; }
    public Duration getTelemetryClockSkew() { return telemetryClockSkew; }
    public void setTelemetryClockSkew(Duration telemetryClockSkew) { this.telemetryClockSkew = telemetryClockSkew; }
    public Duration getProviderDeadline() { return providerDeadline; }
    public void setProviderDeadline(Duration providerDeadline) { this.providerDeadline = providerDeadline; }
    public int getProviderParallelism() { return providerParallelism; }
    public void setProviderParallelism(int providerParallelism) { this.providerParallelism = providerParallelism; }
    public int getProviderQueueCapacity() { return providerQueueCapacity; }
    public void setProviderQueueCapacity(int providerQueueCapacity) { this.providerQueueCapacity = providerQueueCapacity; }
    public int getSponsorCampaignDailyCap() { return sponsorCampaignDailyCap; }
    public void setSponsorCampaignDailyCap(int sponsorCampaignDailyCap) { this.sponsorCampaignDailyCap = sponsorCampaignDailyCap; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) { this.cleanupBatchSize = cleanupBatchSize; }
    public int getCleanupMaxBatches() { return cleanupMaxBatches; }
    public void setCleanupMaxBatches(int cleanupMaxBatches) { this.cleanupMaxBatches = cleanupMaxBatches; }
    public Duration getCleanupTimeBudget() { return cleanupTimeBudget; }
    public void setCleanupTimeBudget(Duration cleanupTimeBudget) { this.cleanupTimeBudget = cleanupTimeBudget; }
    public boolean isCleanupEnabled() { return cleanupEnabled; }
    public void setCleanupEnabled(boolean cleanupEnabled) { this.cleanupEnabled = cleanupEnabled; }
    public int getMaxSessionDeliveries() { return maxSessionDeliveries; }
    public void setMaxSessionDeliveries(int maxSessionDeliveries) { this.maxSessionDeliveries = maxSessionDeliveries; }
    public int getDefaultPageSize() { return defaultPageSize; }
    public void setDefaultPageSize(int defaultPageSize) { this.defaultPageSize = defaultPageSize; }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }
    public int getProviderLimit() { return providerLimit; }
    public void setProviderLimit(int providerLimit) { this.providerLimit = providerLimit; }
    public boolean isMockSponsorsEnabled() { return mockSponsorsEnabled; }
    public void setMockSponsorsEnabled(boolean mockSponsorsEnabled) { this.mockSponsorsEnabled = mockSponsorsEnabled; }
}
