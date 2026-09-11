package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedController;
import com.berkayb.soundconnect.modules.feed.musician.delivery.*;
import com.berkayb.soundconnect.modules.feed.musician.feedback.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MusicianFeedFeatureFlagTest {
    @Test
    void disabledRolloutFlagRemovesAllOperationalFeedHttpEndpoints() {
        new ApplicationContextRunner()
                .withPropertyValues("app.feed.musician.enabled=false")
                .withBean(MusicianFeedService.class, () -> mock(MusicianFeedService.class))
                .withBean(MusicianFeedFeedbackService.class, () -> mock(MusicianFeedFeedbackService.class))
                .withBean(MusicianFeedTelemetryService.class, () -> mock(MusicianFeedTelemetryService.class))
                .withUserConfiguration(MusicianFeedController.class,
                        MusicianFeedFeedbackController.class, MusicianFeedTelemetryController.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MusicianFeedController.class);
                    assertThat(context).doesNotHaveBean(MusicianFeedFeedbackController.class);
                    assertThat(context).doesNotHaveBean(MusicianFeedTelemetryController.class);
                });
    }

    @Test
    void cleanupCanRemainEnabledWhileServingFlagIsDisabled() {
        MusicianFeedProperties properties = new MusicianFeedProperties();
        properties.setEnabled(false);
        properties.setCleanupEnabled(true);
        new ApplicationContextRunner()
                .withPropertyValues("app.feed.musician.enabled=false",
                        "app.feed.musician.cleanup-enabled=true")
                .withBean(MusicianFeedProperties.class, () -> properties)
                .withBean(NamedParameterJdbcTemplate.class,
                        () -> mock(NamedParameterJdbcTemplate.class))
                .withUserConfiguration(MusicianFeedDeliveryCleanup.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(MusicianFeedDeliveryCleanup.class));
    }
}
