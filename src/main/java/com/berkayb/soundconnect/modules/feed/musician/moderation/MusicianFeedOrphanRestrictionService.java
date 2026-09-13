package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import static com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedOrphanRestrictionModels.*;

/** Restores orphaned restrictions without reconstructing an erased reporter or report. */
@Service
@PreAuthorize("hasAuthority('MANAGE_MUSICIAN_FEED_REPORTS')")
public class MusicianFeedOrphanRestrictionService {
    private final MusicianFeedOrphanRestrictionRepository orphans;
    private final MusicianFeedRestrictionRepository restrictions;
    private final MusicianFeedModerationScopeResolver scopes;
    private final MusicianFeedOrphanRestrictionCursor cursors;
    private final Clock clock;

    @Autowired
    public MusicianFeedOrphanRestrictionService(MusicianFeedOrphanRestrictionRepository orphans,
            MusicianFeedRestrictionRepository restrictions, MusicianFeedModerationScopeResolver scopes,
            MusicianFeedOrphanRestrictionCursor cursors) {
        this(orphans, restrictions, scopes, cursors, Clock.systemUTC());
    }

    MusicianFeedOrphanRestrictionService(MusicianFeedOrphanRestrictionRepository orphans,
            MusicianFeedRestrictionRepository restrictions, MusicianFeedModerationScopeResolver scopes,
            MusicianFeedOrphanRestrictionCursor cursors, Clock clock) {
        this.orphans = orphans;
        this.restrictions = restrictions;
        this.scopes = scopes;
        this.cursors = cursors;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page list(UUID actor, Integer requestedLimit, String cursor) {
        requireActor(actor);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 50) throw invalid();
        Instant now = now();
        var position = cursor == null ? null : cursors.decode(actor, cursor, now);
        Instant anchor = position == null ? now : position.anchor();
        var rows = orphans.page(anchor, position, limit + 1);
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (more) {
            var last = selected.getLast();
            next = cursors.encode(actor, new MusicianFeedOrphanRestrictionCursor.Position(anchor, last.appliedAt(), last.reportId()));
        }
        return new Page(selected.stream().map(row -> new Item(row.reportId(),
                scopes.describeScope(row.scopeKey()), row.scopeKey(), row.appliedByUserId(), row.appliedAt(), row.updatedAt())).toList(), next, more);
    }

    @Transactional
    public Restored restore(UUID actor, UUID reportId, RestoreRequest request) {
        requireActor(actor);
        String note = normalize(reportId, request);
        String hash = hash(actor, request, note);
        // This path locks only the independent restriction. It never locks a
        // surviving report in the opposite order to the normal review service.
        var row = orphans.lock(reportId).orElseThrow(() -> new SoundConnectException(ErrorType.MUSICIAN_FEED_REPORT_NOT_FOUND));
        if (orphans.reportExists(reportId)) throw conflict();
        var prior = orphans.requestHash(reportId, request.clientRequestId());
        if (prior.isPresent()) {
            if (!prior.get().equals(hash)) throw conflict();
            return response(row);
        }
        if (!row.active() || !row.updatedAt().equals(request.expectedUpdatedAt())) throw conflict();
        Instant now = now();
        restrictions.restore(reportId, now);
        orphans.append(reportId, request.clientRequestId(), hash, actor, note, now);
        return response(new MusicianFeedOrphanRestrictionRepository.Row(row.reportId(), row.scopeKey(), false,
                row.appliedByUserId(), row.appliedAt(), now));
    }

    private Restored response(MusicianFeedOrphanRestrictionRepository.Row row) {
        boolean active = restrictions.activeScopes(List.of(row.scopeKey())).contains(row.scopeKey());
        return new Restored(row.reportId(), row.active(), row.updatedAt(), active);
    }

    private String normalize(UUID reportId, RestoreRequest request) {
        if (reportId == null || request == null || request.clientRequestId() == null
                || request.expectedUpdatedAt() == null || request.resolutionNote() == null) throw invalid();
        String note = request.resolutionNote().strip();
        if (note.length() < 5 || note.length() > 500 || note.codePoints().anyMatch(value ->
                (Character.isISOControl(value) && value != '\n' && value != '\t')
                        || (value >= 0xd800 && value <= 0xdfff))) throw invalid();
        return note;
    }

    private String hash(UUID actor, RestoreRequest request, String note) {
        String value = actor + "\u0000" + request.expectedUpdatedAt() + "\u0000" + note;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void requireActor(UUID actor) {
        if (actor == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.BAD_REQUEST); }
    private SoundConnectException conflict() { return new SoundConnectException(ErrorType.MUSICIAN_FEED_REPORT_CONFLICT); }
}
