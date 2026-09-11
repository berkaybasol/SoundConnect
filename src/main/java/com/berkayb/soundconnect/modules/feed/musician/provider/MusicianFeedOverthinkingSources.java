package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keeps feed projections on the canonical viewer-aware Overthinking privacy path. */
final class MusicianFeedOverthinkingSources {
    private static final int CANONICAL_BATCH_LIMIT = 50;

    private MusicianFeedOverthinkingSources() { }

    static Map<UUID, OverthinkingPostResponseDto> resolve(
            OverthinkingPostService posts,
            UUID viewerUserId,
            Collection<UUID> sourceIds
    ) {
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        if (sourceIds != null) {
            for (UUID id : sourceIds) if (id != null) unique.add(id);
        }
        if (unique.isEmpty()) return Map.of();

        List<UUID> ordered = new ArrayList<>(unique);
        Map<UUID, OverthinkingPostResponseDto> result = new LinkedHashMap<>();
        for (int from = 0; from < ordered.size(); from += CANONICAL_BATCH_LIMIT) {
            int to = Math.min(ordered.size(), from + CANONICAL_BATCH_LIMIT);
            Map<UUID, OverthinkingPostResponseDto> page = posts.getByIdsForViewer(
                    viewerUserId, List.copyOf(ordered.subList(from, to)));
            if (page != null) result.putAll(page);
        }
        return Map.copyOf(result);
    }
}
