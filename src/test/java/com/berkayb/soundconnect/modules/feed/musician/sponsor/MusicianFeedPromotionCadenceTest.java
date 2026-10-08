package com.berkayb.soundconnect.modules.feed.musician.sponsor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class MusicianFeedPromotionCadenceTest {
    private static final UUID VIEWER = UUID.fromString("15a5e2b1-8ab3-46de-b4b0-b8fb3437bc50");
    private static final Instant ANCHOR = Instant.parse("2026-09-12T12:00:00.123456789Z");

    @Test
    void sameSessionReplayKeepsItsGapsAndOrdinalsVaryWithinTheBoundedRange() {
        var firstRead = LongStream.range(0, 128)
                .mapToInt(ordinal -> MusicianFeedPromotionCadence.organicGap(VIEWER, ANCHOR, ordinal))
                .boxed().toList();
        var replay = LongStream.range(0, 128)
                .mapToInt(ordinal -> MusicianFeedPromotionCadence.organicGap(VIEWER, ANCHOR, ordinal))
                .boxed().toList();

        assertThat(replay).containsExactlyElementsOf(firstRead);
        assertThat(firstRead).allSatisfy(gap -> assertThat(gap).isBetween(6, 10));
        assertThat(new HashSet<>(firstRead)).containsExactlyInAnyOrder(6, 7, 8, 9, 10);
        assertThat(MusicianFeedPromotionCadence.organicGap(VIEWER, ANCHOR, Long.MAX_VALUE))
                .isBetween(6, 10);
    }

    @Test
    void viewerAndRefreshAnchorBothInfluenceTheFirstSponsorPosition() {
        Set<Integer> viewerGaps = new HashSet<>();
        Set<Integer> refreshGaps = new HashSet<>();
        for (int index = 0; index < 256; index++) {
            viewerGaps.add(MusicianFeedPromotionCadence.organicGap(new UUID(31, index), ANCHOR, 0));
            refreshGaps.add(MusicianFeedPromotionCadence.organicGap(VIEWER, ANCHOR.plusSeconds(index), 0));
        }

        assertThat(viewerGaps).containsExactlyInAnyOrder(6, 7, 8, 9, 10);
        assertThat(refreshGaps).containsExactlyInAnyOrder(6, 7, 8, 9, 10);
    }

    @Test
    void deterministicPopulationStaysNearOnePromotionPerEightOrganicItems() {
        int[] occurrences = new int[5];
        long totalGap = 0;
        int sampleSize = 64 * 128;
        for (int viewerIndex = 0; viewerIndex < 64; viewerIndex++) {
            UUID viewer = new UUID(913, viewerIndex);
            Instant anchor = ANCHOR.plusSeconds(viewerIndex * 137L);
            for (int ordinal = 0; ordinal < 128; ordinal++) {
                int gap = MusicianFeedPromotionCadence.organicGap(viewer, anchor, ordinal);
                assertThat(gap).isBetween(6, 10);
                occurrences[gap - 6]++;
                totalGap += gap;
            }
        }

        assertThat(totalGap / (double) sampleSize).isBetween(7.9, 8.1);
        for (int count : occurrences) {
            assertThat(count / (double) sampleSize).isBetween(0.17, 0.23);
        }
    }
}
