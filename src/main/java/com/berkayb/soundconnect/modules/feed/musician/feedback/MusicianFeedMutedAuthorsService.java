package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedMutedAuthorsResponse;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class MusicianFeedMutedAuthorsService {
    private final MusicianFeedViewerGuard viewers;
    private final MusicianFeedMutedAuthorsRepository repository;
    private final MusicianFeedMutedAuthorsCursorCodec cursors;
    private final Clock clock;

    @Autowired
    public MusicianFeedMutedAuthorsService(MusicianFeedViewerGuard viewers,
                                           MusicianFeedMutedAuthorsRepository repository,
                                           MusicianFeedMutedAuthorsCursorCodec cursors) {
        this(viewers, repository, cursors, Clock.systemUTC());
    }

    MusicianFeedMutedAuthorsService(MusicianFeedViewerGuard viewers,
                                    MusicianFeedMutedAuthorsRepository repository,
                                    MusicianFeedMutedAuthorsCursorCodec cursors, Clock clock) {
        this.viewers = viewers;
        this.repository = repository;
        this.cursors = cursors;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MusicianFeedMutedAuthorsResponse get(UUID viewerId, Integer requestedLimit, String cursor) {
        viewers.requireMusicianProfile(viewerId);
        int limit = requestedLimit == null ? 30 : requestedLimit;
        if (limit < 1 || limit > 50) throw new SoundConnectException(ErrorType.BAD_REQUEST);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var after = cursor == null || cursor.isBlank() ? null : cursors.decode(cursor, viewerId, now);
        Instant anchor = after == null ? now : after.anchor();
        var rows = repository.findPage(viewerId, anchor, after, limit + 1);
        boolean hasMore = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        String nextCursor = null;
        if (hasMore) {
            var last = selected.getLast();
            nextCursor = cursors.encode(viewerId, new MusicianFeedMutedAuthorsCursorCodec.Position(
                    anchor, last.author().mutedAt(), last.feedbackId()));
        }
        return new MusicianFeedMutedAuthorsResponse(selected.stream().map(
                MusicianFeedMutedAuthorsRepository.Row::author).toList(), nextCursor, hasMore);
    }
}
