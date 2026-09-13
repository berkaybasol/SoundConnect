package com.berkayb.soundconnect.modules.feed.musician.announcement;

import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementResponse;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementStatus;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class MusicianFeedAnnouncementSelectorTest {
    private static final UUID VIEWER = new UUID(5, 9);
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    @Test void newestAnnouncementGetsAnEarlySlotOnlyOnItsFirstQualifiedExposure() {
        var latestUnseen = value(1, NOW);
        var olderSeen = value(2, NOW.minusSeconds(1));
        var olderUnseen = value(3, NOW.minusSeconds(2));
        var selector = new MusicianFeedAnnouncementSelector(VIEWER, new UUID(1, 2));
        selector.consider(olderSeen, 1);
        selector.consider(olderUnseen, 0);
        selector.consider(latestUnseen, 0);
        assertThat(selector.selected()).hasSize(3);
        assertThat(selector.selected().getFirst().value().id()).isEqualTo(latestUnseen.id());
        assertThat(selector.selected().getFirst().placement().gap()).isBetween(1, 2);
        assertThat(selector.selected().subList(1, 3)).allSatisfy(row ->
                assertThat(row.placement().gap()).isBetween(4, 8));
    }

    @Test void seenNewestDoesNotGiveOlderUnseenAnnouncementsItsEarlySlot() {
        var newestSeen = value(1, NOW);
        var olderUnseen = value(2, NOW.minusSeconds(1));
        var oldestUnseen = value(3, NOW.minusSeconds(2));
        for (var values : List.of(List.of(newestSeen, olderUnseen, oldestUnseen),
                List.of(oldestUnseen, olderUnseen, newestSeen))) {
            var selector = new MusicianFeedAnnouncementSelector(VIEWER, new UUID(1, 2));
            values.forEach(value -> selector.consider(value, value.id().equals(newestSeen.id()) ? 1 : 0));
            assertThat(selector.selected()).hasSize(3).allSatisfy(row ->
                    assertThat(row.placement().gap()).isBetween(4, 8));
            assertThat(selector.selected()).extracting(row -> row.value().id())
                    .contains(olderUnseen.id(), oldestUnseen.id());
        }
    }

    @Test void publicationTimeTiesUseTheSameNewestIdentityRegardlessOfSeenCountsOrQueryOrder() {
        var newestByTie = value(1, NOW);
        var otherUnseen = value(2, NOW);
        for (var values : List.of(List.of(newestByTie, otherUnseen), List.of(otherUnseen, newestByTie))) {
            var selector = new MusicianFeedAnnouncementSelector(VIEWER, new UUID(1, 2));
            values.forEach(value -> selector.consider(value, value.id().equals(newestByTie.id()) ? 1 : 0));
            assertThat(selector.selected()).hasSize(2).allSatisfy(row ->
                    assertThat(row.placement().gap()).isBetween(4, 8));
        }
    }

    @Test void theSameSessionIsStableAcrossQueryOrderAndEverySeenSlotUsesTheNormalGap() {
        List<AnnouncementResponse> values = new ArrayList<>();
        for (int index = 0; index < 80; index++) values.add(value(index, NOW.minusSeconds(index)));
        var first = select(values, new UUID(3, 4));
        Collections.shuffle(values, new Random(42));
        assertThat(select(values, new UUID(3, 4))).isEqualTo(first);
        assertThat(first).allSatisfy(row -> assertThat(row.placement().gap()).isBetween(4, 8));
        Set<UUID> chosen = new HashSet<>();
        Set<Integer> gaps = new HashSet<>();
        for (int session = 0; session < 40; session++) {
            for (var row : select(values, new UUID(10, session))) {
                chosen.add(row.value().id());
                gaps.add(row.placement().gap());
            }
        }
        assertThat(chosen.size()).isGreaterThan(3);
        assertThat(gaps).hasSizeGreaterThan(1);
    }

    @Test void repeatedSessionsFavorLessSeenOlderContentWithoutMakingOthersImpossible() {
        int olderSelections = 0;
        int heavilySeenSelections = 0;
        for (int session = 0; session < 200; session++) {
            var selector = new MusicianFeedAnnouncementSelector(VIEWER, new UUID(70, session));
            selector.consider(value(1, NOW.minusSeconds(1000)), 1);
            for (int id = 2; id <= 8; id++) selector.consider(value(id, NOW.minusSeconds(id)), 99);
            var selected = selector.selected();
            if (selected.stream().anyMatch(row -> row.value().id().equals(new UUID(0, 1)))) olderSelections++;
            if (selected.stream().anyMatch(row -> row.value().id().equals(new UUID(0, 2)))) heavilySeenSelections++;
        }
        assertThat(olderSelections).isGreaterThan(180);
        assertThat(heavilySeenSelections).isBetween(1, 160);
    }

    @Test void signedPlanShapeCannotCarryDuplicatesMoreThanThreeOrUnsafeLaterGaps() {
        var a = new MusicianFeedAnnouncementPlan.Entry(new UUID(0, 1), 1);
        assertThatThrownBy(() -> new MusicianFeedAnnouncementPlan(List.of(a, a))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MusicianFeedAnnouncementPlan(List.of(a,
                new MusicianFeedAnnouncementPlan.Entry(new UUID(0, 2), 3)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MusicianFeedAnnouncementPlan(List.of(a,
                new MusicianFeedAnnouncementPlan.Entry(new UUID(0, 2), 4),
                new MusicianFeedAnnouncementPlan.Entry(new UUID(0, 3), 5),
                new MusicianFeedAnnouncementPlan.Entry(new UUID(0, 4), 6)))).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<MusicianFeedAnnouncementSelector.Selected> select(List<AnnouncementResponse> values, UUID session) {
        var selector = new MusicianFeedAnnouncementSelector(VIEWER, session);
        values.forEach(value -> selector.consider(value, 2));
        return selector.selected();
    }

    static AnnouncementResponse value(long id, Instant published) {
        return new AnnouncementResponse(new UUID(0, id), 0, "Platform duyurusu", "Duyuru açıklaması",
                Set.of(ProfileType.MUSICIAN), AnnouncementStatus.PUBLISHED, null, null, published,
                published, published, null, new AnnouncementResponse.Engagement(0, 0, false), false);
    }
}
