package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Stable reported identity and immutable report-time evidence for policy adapters. */
public record MusicianFeedModerationSubject(UUID reportId, String itemId, String itemType,
                                           String targetType, UUID targetId, JsonNode evidence) { }
