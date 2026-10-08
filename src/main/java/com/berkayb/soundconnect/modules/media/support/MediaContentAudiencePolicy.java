package com.berkayb.soundconnect.modules.media.support;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaContentAudience;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Request audience comes from authenticated authorities, never from a client query parameter. */
public final class MediaContentAudiencePolicy {
    private MediaContentAudiencePolicy() { }

    public static boolean isListenerViewer() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_LISTENER".equals(authority.getAuthority()));
    }

    public static boolean canRead(MediaAsset asset) {
        return asset != null && (!isListenerViewer()
                || asset.getContentAudience() == MediaContentAudience.MAINSTAGE);
    }

    public static void requireStudioAccess() {
        if (isListenerViewer()) throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
    }
}
