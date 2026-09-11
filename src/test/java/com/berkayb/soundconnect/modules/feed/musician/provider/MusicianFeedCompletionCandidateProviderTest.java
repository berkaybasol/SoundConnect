package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidateRequest;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MusicianFeedCompletionCandidateProviderTest {
    private final MusicianFeedCompletionCandidateProvider provider = new MusicianFeedCompletionCandidateProvider();

    @Test
    void emitsOneAuthoritativeNudgeOnlyWhenIncompleteAndAdvertised() {
        var task = new MusicianFeedPayloads.CompletionTask("INSTRUMENTS", "Enstrümanlarını ekle",
                "Daha iyi eşleşmeler al.", "Ekle", "/profile/musician/edit", 2, false);
        var completion = new MusicianFeedPayloads.Completion(3, 5, List.of(task));
        UUID profileId = UUID.randomUUID();
        MusicianFeedCandidateRequest request = request(profileId,
                Set.of(MusicianFeedItemType.PROFILE_COMPLETION), completion);

        var candidates = provider.findCandidates(request);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.itemId()).isEqualTo("PROFILE_COMPLETION:" + profileId);
            assertThat(candidate.reason().code()).isEqualTo(MusicianFeedReasonCode.PROFILE_INCOMPLETE);
            assertThat(candidate.payload()).isEqualTo(completion);
            assertThat(candidate.feedbackCapabilities()).containsExactly(MusicianFeedFeedbackAction.HIDE);
            assertThat(candidate.ownedByViewer()).isFalse();
        });
    }

    @Test
    void missingCompletionOrUnsupportedRendererProducesNoCandidate() {
        UUID profileId = UUID.randomUUID();
        assertThat(provider.findCandidates(request(profileId, Set.of(MusicianFeedItemType.TRACK),
                new MusicianFeedPayloads.Completion(0, 5, List.of())))).isEmpty();
        assertThat(provider.findCandidates(request(profileId, Set.of(MusicianFeedItemType.PROFILE_COMPLETION),
                null))).isEmpty();
    }

    @Test
    void standardItemCapabilitiesNeverLeakTheAuthorMutePersistenceAction() {
        assertThat(MusicianFeedJdbcSupport.standardFeedback()).containsExactly(
                MusicianFeedFeedbackAction.HIDE,
                MusicianFeedFeedbackAction.SHOW_LESS,
                MusicianFeedFeedbackAction.REPORT);
    }

    private static MusicianFeedCandidateRequest request(
            UUID profileId,
            Set<MusicianFeedItemType> types,
            MusicianFeedPayloads.Completion completion
    ) {
        Instant now = Instant.parse("2026-09-11T12:00:00Z");
        return new MusicianFeedCandidateRequest(UUID.randomUUID(), profileId, UUID.randomUUID(), now, now,
                20, types, new MusicianFeedPersonalizationSnapshot(null, Set.of(), completion),
                MusicianFeedFeedbackSnapshot.empty());
    }
}
