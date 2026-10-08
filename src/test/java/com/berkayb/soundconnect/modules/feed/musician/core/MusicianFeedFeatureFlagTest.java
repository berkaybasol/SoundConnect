package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedController;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidateProvider;
import com.berkayb.soundconnect.modules.feed.musician.cursor.MusicianFeedCursorCodec;
import com.berkayb.soundconnect.modules.feed.musician.delivery.*;
import com.berkayb.soundconnect.modules.feed.musician.feedback.*;
import com.berkayb.soundconnect.modules.feed.musician.mixer.MusicianFeedMixer;
import com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionGuard;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSource;
import com.berkayb.soundconnect.modules.feed.musician.sponsor.MusicianFeedSponsorshipProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MusicianFeedFeatureFlagTest {
    @Test
    void organicFeedStartsWithoutAnySponsorshipProvider() {
        new ApplicationContextRunner()
                .withUserConfiguration(MusicianFeedService.class)
                .withBean(MusicianFeedProperties.class, MusicianFeedProperties::new)
                .withBean(MusicianFeedMetrics.class, MusicianFeedMetrics::unbound)
                .withBean(MusicianFeedViewerGuard.class, () -> mock(MusicianFeedViewerGuard.class))
                .withBean(MusicianFeedCursorCodec.class, () -> mock(MusicianFeedCursorCodec.class))
                .withBean(MusicianFeedMixer.class, () -> mock(MusicianFeedMixer.class))
                .withBean(MusicianFeedFeedbackService.class, () -> mock(MusicianFeedFeedbackService.class))
                .withBean(MusicianFeedRestrictionGuard.class, () -> mock(MusicianFeedRestrictionGuard.class))
                .withBean(MusicianFeedPersonalizationSource.class,
                        () -> mock(MusicianFeedPersonalizationSource.class))
                .withBean(MusicianFeedDeliveryService.class, () -> mock(MusicianFeedDeliveryService.class))
                .withBean(MusicianFeedCandidateProvider.class, () -> mock(MusicianFeedCandidateProvider.class))
                .withBean("musicianFeedProviderExecutor", ExecutorService.class,
                        () -> mock(ExecutorService.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(MusicianFeedService.class)
                        .doesNotHaveBean(MusicianFeedSponsorshipProvider.class));
    }

    @Test
    void disabledRolloutFlagRemovesAllOperationalFeedHttpEndpoints() {
        new ApplicationContextRunner()
                .withPropertyValues("app.feed.musician.enabled=false")
                .withBean(MusicianFeedService.class, () -> mock(MusicianFeedService.class))
                .withBean(MusicianFeedFeedbackService.class, () -> mock(MusicianFeedFeedbackService.class))
                .withBean(MusicianFeedTelemetryService.class, () -> mock(MusicianFeedTelemetryService.class))
                .withUserConfiguration(MusicianFeedController.class,
                        MusicianFeedFeedbackController.class, MusicianFeedTelemetryController.class,
                        com.berkayb.soundconnect.modules.feed.venue.api.VenueFeedController.class,
                        com.berkayb.soundconnect.modules.feed.listener.api.ListenerFeedController.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MusicianFeedController.class);
                    assertThat(context).doesNotHaveBean(MusicianFeedFeedbackController.class);
                    assertThat(context).doesNotHaveBean(MusicianFeedTelemetryController.class);
                    assertThat(context).doesNotHaveBean(com.berkayb.soundconnect.modules.feed.venue.api.VenueFeedController.class);
                    assertThat(context).doesNotHaveBean(com.berkayb.soundconnect.modules.feed.listener.api.ListenerFeedController.class);
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
