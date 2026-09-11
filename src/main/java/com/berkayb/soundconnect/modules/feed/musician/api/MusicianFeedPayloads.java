package com.berkayb.soundconnect.modules.feed.musician.api;

import com.berkayb.soundconnect.modules.collab.dto.response.CollabListingResponse;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Feed-local, public-safe payload projections. */
public final class MusicianFeedPayloads {
    private MusicianFeedPayloads() { }

    public record Track(
            UUID trackId,
            UUID mediaAssetId,
            String title,
            String playbackUrl,
            Integer durationSeconds,
            Integer bpm
    ) { }

    public record ProfileMedia(
            UUID mediaAssetId,
            String kind,
            String displayUrl,
            String playbackUrl,
            String thumbnailUrl,
            String title,
            String description,
            Integer durationSeconds,
            Integer width,
            Integer height
    ) { }

    public record Collab(CollabListingResponse listing) { }

    public record Event(EventResponseDto event, String note, UUID publicationId) { }

    public record Profile(
            UUID profileId,
            String profileType,
            UUID userId,
            String username,
            String displayName,
            String avatarUrl,
            String bio,
            String location,
            boolean followedByViewer
    ) { }

    public record ProfileShare(
            UUID shareId,
            String note,
            Instant publishedAt,
            Object source
    ) { }

    public record Activity(
            String action,
            MusicianFeedItemResponse.Author actor,
            MusicianFeedItemType targetItemType,
            Object targetPayload
    ) {
        /** Backward-compatible constructor for the v1 JSON contract. */
        public Activity(String action, MusicianFeedItemType targetItemType, Object targetPayload) {
            this(action, null, targetItemType, targetPayload);
        }
    }

    public record Completion(
            int completed,
            int total,
            List<CompletionTask> tasks
    ) {
        public Completion {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    public record CompletionTask(
            String code,
            String title,
            String description,
            String ctaLabel,
            String route,
            int priority,
            boolean complete
    ) { }

    public record SponsoredStandalone(
            String title,
            String body,
            String mediaUrl,
            String ctaLabel,
            String ctaUrl
    ) { }
}
