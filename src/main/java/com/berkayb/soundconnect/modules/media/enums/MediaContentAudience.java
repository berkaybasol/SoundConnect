package com.berkayb.soundconnect.modules.media.enums;

/** Content destination, independent of PUBLIC/UNLISTED/PRIVATE storage visibility. */
public enum MediaContentAudience {
    MAINSTAGE,
    BACKSTAGE;

    public static MediaContentAudience forOwner(MediaOwnerType ownerType, MediaContentAudience requested) {
        // Collab is a typed business publication; it has no MediaOwnerType/attachment today.
        if (ownerType == MediaOwnerType.STUDIO_PROFILE) return BACKSTAGE;
        return requested == null ? MAINSTAGE : requested;
    }
}
