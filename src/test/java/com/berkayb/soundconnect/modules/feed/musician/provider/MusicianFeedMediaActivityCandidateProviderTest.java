package com.berkayb.soundconnect.modules.feed.musician.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MusicianFeedMediaActivityCandidateProviderTest {

    @Test
    void actionReservationIsTenPercentRoundedUpOnlyWhenBothActionsAreRequested() {
        assertThat(MusicianFeedMediaActivityCandidateProvider.actionReservation(160, true, true))
                .isEqualTo(16);
        assertThat(MusicianFeedMediaActivityCandidateProvider.actionReservation(11, true, true))
                .isEqualTo(2);
        assertThat(MusicianFeedMediaActivityCandidateProvider.actionReservation(1, true, true))
                .isEqualTo(1);
        assertThat(MusicianFeedMediaActivityCandidateProvider.actionReservation(160, true, false))
                .isZero();
        assertThat(MusicianFeedMediaActivityCandidateProvider.actionReservation(160, false, true))
                .isZero();
    }
}
