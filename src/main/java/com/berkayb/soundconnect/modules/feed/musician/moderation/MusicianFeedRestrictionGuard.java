package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MusicianFeedRestrictionGuard {
    private final MusicianFeedRestrictionRepository restrictions;
    private final MusicianFeedModerationScopeResolver scopes;

    public MusicianFeedRestrictionGuard(MusicianFeedRestrictionRepository restrictions, MusicianFeedModerationScopeResolver scopes) {
        this.restrictions = restrictions;
        this.scopes = scopes;
    }

    public List<MusicianFeedCandidate> filter(Collection<MusicianFeedCandidate> candidates) {
        if (candidates.size() > 10_000) throw new IllegalArgumentException("Too many feed candidates");
        Map<MusicianFeedCandidate, Set<String>> byCandidate = new LinkedHashMap<>();
        candidates.forEach(candidate -> byCandidate.put(candidate, scopes.presentationScopes(candidate.toResponse())));
        Set<String> blocked = restrictions.activeScopes(byCandidate.values().stream().flatMap(Collection::stream).toList());
        return byCandidate.entrySet().stream().filter(entry -> Collections.disjoint(entry.getValue(), blocked))
                .map(Map.Entry::getKey).toList();
    }

    public void requireUnrestricted(Collection<MusicianFeedItemResponse> items) {
        if (items.size() > 100) throw invalid();
        try {
            List<String> requested = items.stream().flatMap(item -> scopes.presentationScopes(item).stream()).toList();
            if (!restrictions.activeScopes(requested).isEmpty()) throw invalid();
        } catch (IllegalArgumentException unresolved) { throw invalid(); }
    }

    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.MUSICIAN_FEED_CURSOR_INVALID); }
}
