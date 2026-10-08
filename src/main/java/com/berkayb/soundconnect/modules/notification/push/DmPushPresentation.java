package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.UUID;

/** Resolves current public identity; never uses a queued identity or message preview. */
@Component
public class DmPushPresentation {
    public static final String VERSION = "ANDROID_DM_V1";
    public static final String BODY = "Sana bir mesaj gönderdi.";
    public record Sender(String name, String avatarUrl) { }
    private final PublicProfileResolverService profiles;
    private final UserRepository users;
    private final String avatarHost;

    public DmPushPresentation(PublicProfileResolverService profiles, UserRepository users,
            @Value("${cloud.storage.cdnBaseUrl:}") String cdnBaseUrl) {
        this.profiles = profiles;
        this.users = users;
        this.avatarHost = host(cdnBaseUrl);
    }

    @Transactional
    public Sender resolve(UUID senderId) {
        if (senderId == null) return anonymous();
        try {
            // The public resolver gives ghost identity precedence over any
            // legacy alternate profiles and returns no identity before choice.
            var resolved = profiles.resolveByUserId(senderId);
            if (resolved == null || !senderId.equals(resolved.userId())
                    || resolved.profiles() == null || resolved.profiles().isEmpty()) return anonymous();
            // Defense in depth if legacy/corrupt multi-profile data reaches
            // this boundary: a ghost pseudonym always wins over a venue/name.
            var ghost = resolved.profiles().stream()
                    .filter(p -> p.visibilityMode() == ListenerVisibilityMode.GHOST).findFirst();
            if (ghost.isPresent()) {
                return new Sender(safeName(ghost.get().displayName()), safeAvatar(ghost.get().profilePictureUrl()));
            }
            UserProfileTargetDto selected = resolved.profiles().stream()
                    .filter(p -> "VENUE".equals(p.type())).findFirst().orElse(resolved.profiles().getFirst());
            // The enclosing transaction keeps the resolver's visibility lock
            // through this fresh scalar read, rather than using a cached User.
            var publicUsername = users.findPublicUsernameForDmPush(senderId);
            if (publicUsername.isEmpty()) return anonymous();
            String name = publicUsername.get().isBlank() ? selected.displayName() : publicUsername.get();
            return new Sender(safeName(name), safeAvatar(selected.profilePictureUrl()));
        } catch (RuntimeException unavailable) {
            // Profile unavailability must not expose a stale pre-ghost snapshot.
            return anonymous();
        }
    }

    public static Sender anonymous() { return new Sender("Kullanıcı", ""); }

    static String safeName(String value) {
        if (value == null) return "Kullanıcı";
        String clean = value.codePoints().filter(c -> !Character.isISOControl(c)
                && Character.getType(c) != Character.FORMAT).limit(80)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().trim();
        return clean.isEmpty() ? "Kullanıcı" : clean;
    }

    String safeAvatar(String value) {
        if (value == null || value.length() > 1500 || avatarHost.isEmpty()) return "";
        try {
            URI uri = URI.create(value);
            // Only the configured public CDN; never signed/private media URLs,
            // redirects, credentials, local origins or arbitrary profile links.
            return "https".equalsIgnoreCase(uri.getScheme()) && avatarHost.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) ? uri.toASCIIString() : "";
        } catch (IllegalArgumentException invalid) { return ""; }
    }

    private static String host(String value) {
        try { return "https".equalsIgnoreCase(URI.create(value).getScheme())
                ? java.util.Objects.requireNonNullElse(URI.create(value).getHost(), "") : ""; }
        catch (RuntimeException invalid) { return ""; }
    }
}
