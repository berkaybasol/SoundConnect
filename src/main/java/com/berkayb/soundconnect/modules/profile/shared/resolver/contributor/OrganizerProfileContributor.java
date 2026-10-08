package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository.OrganizerProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrganizerProfileContributor implements PublicProfileContributor {
    private final OrganizerProfileRepository profiles;
    private final MediaAssetService media;

    @Override
    public String type() { return "ORGANIZER"; }

    @Override
    public List<UserProfileTargetDto> resolve(UUID userId) {
        if (userId == null) return List.of();
        return profiles.findByUserId(userId)
                .filter(profile -> isPublicAccount(profile.getUser(), userId))
                .map(profile -> List.of(new UserProfileTargetDto(type(), profile.getId(),
                        publicUsername(profile.getUser()), displayUrl(profile.getProfilePictureMediaId()))))
                .orElse(List.of());
    }

    private boolean isPublicAccount(User user, UUID userId) {
        return user != null && userId.equals(user.getId()) && user.getErasedAt() == null
                && user.getStatus() == UserStatus.ACTIVE && Boolean.TRUE.equals(user.getEmailVerified());
    }

    private String publicUsername(User user) {
        String username = user.getUsername();
        return username == null || username.isBlank() ? "Kullanıcı" : username.trim();
    }

    private String displayUrl(UUID mediaId) {
        if (mediaId == null) return null;
        try {
            return media.getDisplayUrl(mediaId);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
