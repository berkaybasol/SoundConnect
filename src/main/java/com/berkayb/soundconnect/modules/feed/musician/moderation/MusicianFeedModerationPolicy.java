package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Runs inside the moderation service's locked report transaction; changes no source content. */
@Component
public class MusicianFeedModerationPolicy {
    private final MusicianFeedRestrictionRepository restrictions;
    private final MusicianFeedModerationScopeResolver scopes;

    public MusicianFeedModerationPolicy(MusicianFeedRestrictionRepository restrictions, MusicianFeedModerationScopeResolver scopes) {
        this.restrictions = restrictions;
        this.scopes = scopes;
    }

    public String describe(MusicianFeedModerationSubject subject) {
        String scope = restrictions.byReport(subject.reportId()).map(MusicianFeedRestrictionRepository.State::scopeKey)
                .orElseGet(() -> scopes.reportScope(subject));
        return scopes.describeScope(scope);
    }

    public boolean isRestricted(MusicianFeedModerationSubject subject) {
        return restrictions.byReport(subject.reportId()).map(MusicianFeedRestrictionRepository.State::active).orElse(false);
    }

    public boolean isSubjectRestricted(MusicianFeedModerationSubject subject) {
        String scope = restrictions.byReport(subject.reportId()).map(MusicianFeedRestrictionRepository.State::scopeKey)
                .orElseGet(() -> scopes.reportScope(subject));
        return scope != null && !restrictions.activeScopes(java.util.List.of(scope)).isEmpty();
    }

    public void remove(MusicianFeedModerationSubject subject, UUID actorUserId, Instant now) {
        String scope = restrictions.byReport(subject.reportId()).map(MusicianFeedRestrictionRepository.State::scopeKey)
                .orElseGet(() -> scopes.reportScope(subject));
        if (subject.reportId() == null || actorUserId == null || now == null || scope == null) throw invalid();
        restrictions.apply(subject.reportId(), scope, actorUserId, now);
    }

    public void restore(MusicianFeedModerationSubject subject, UUID actorUserId, Instant now) {
        if (subject.reportId() == null || actorUserId == null || now == null) throw invalid();
        restrictions.restore(subject.reportId(), now);
    }

    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.BAD_REQUEST); }
}
