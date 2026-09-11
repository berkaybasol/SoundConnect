package com.berkayb.soundconnect.modules.feed.musician.delivery;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MusicianFeedMigrationOrderTest {
    @Test
    void localBootstrapAppliesDeliveryBeforeFeedbackAndOnlineIndexesLast() throws Exception {
        String runner = Files.readString(Path.of("scripts/dev.ps1"));
        int preferences = runner.indexOf("2026-09-11-musician-feed-preferences.sql");
        int overthinkingEngagement = runner.indexOf("2026-09-11-overthinking-profile-share-engagement.sql");
        int delivery = runner.indexOf("2026-09-11-musician-feed-delivery.sql");
        int replay = runner.indexOf("2026-09-11-musician-feed-replay.sql");
        int feedback = runner.indexOf("2026-09-11-musician-feed-feedback.sql");
        int indexes = runner.indexOf("2026-09-11-musician-feed-indexes.sql");

        assertThat(preferences).isGreaterThanOrEqualTo(0);
        assertThat(overthinkingEngagement).isGreaterThan(preferences);
        assertThat(delivery).isGreaterThan(overthinkingEngagement);
        assertThat(replay).isGreaterThan(delivery);
        assertThat(feedback).isGreaterThan(replay);
        assertThat(indexes).isGreaterThan(feedback);
    }
}
