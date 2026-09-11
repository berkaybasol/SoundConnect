package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class MusicianFeedCompletionCandidateProvider implements MusicianFeedCandidateProvider {
    @Override public String providerId() { return "profile-completion"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() { return Set.of(MusicianFeedItemType.PROFILE_COMPLETION); }
    @Override public boolean optional() { return false; }

    @Override
    public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        var completion = request.personalization().completion();
        if (!request.supportedTypes().contains(MusicianFeedItemType.PROFILE_COMPLETION)
                || completion == null || completion.tasks().isEmpty()) return List.of();
        String itemId = "PROFILE_COMPLETION:" + request.musicianProfileId();
        return List.of(new MusicianFeedCandidate(itemId, MusicianFeedItemType.PROFILE_COMPLETION, 1,
                request.anchor(),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.PROFILE_INCOMPLETE, List.of(), 0),
                null,
                new MusicianFeedItemResponse.Target("PROFILE", request.musicianProfileId()),
                null, null, List.of(MusicianFeedFeedbackAction.HIDE), completion,
                1_200_000L, 0, MusicianFeedLane.SYSTEM, false));
    }
}
