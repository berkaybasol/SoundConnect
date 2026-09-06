package com.berkayb.soundconnect.modules.event.support;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;

import java.util.UUID;

/** Shared display policy for event cards and authorized performer requests. */
public final class EventPosterResolver {
    private EventPosterResolver() { }

    public static String resolve(String raw, MediaAssetService mediaAssetService) {
        if (raw == null || raw.isBlank()) return null;
        final UUID assetId;
        try {
            assetId = UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return raw;
        }
        try {
            return mediaAssetService.getDisplayUrl(assetId);
        } catch (Exception ignored) {
            // A missing/unavailable poster must not block an approval decision.
            return null;
        }
    }
}
