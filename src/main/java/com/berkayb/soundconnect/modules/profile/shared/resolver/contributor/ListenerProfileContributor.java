package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ListenerProfileContributor implements PublicProfileContributor {
	
	private final ListenerProfileRepository listenerProfileRepository;
	private final MediaAssetService mediaAssetService;
	
	@Override
	public String type() {
		return "LISTENER";
	}
	
	@Override
	public List<UserProfileTargetDto> resolve(UUID userId) {
		return listenerProfileRepository.findForPublicIdentityByUserId(userId)
		                                .filter(ListenerProfile::isVisibilityChoiceCompleted)
		                                .map(lp -> List.of(
				                                new UserProfileTargetDto(
						                                type(),
						                                lp.getId(),
						                                resolveDisplayName(lp),
						                                resolveMediaUrl(lp.getProfilePictureMediaId()),
						                                publicVisibilityMode(lp.getVisibilityMode())
				                                )
		                                ))
		                                .orElse(List.of());
	}
	
	private String resolveDisplayName(ListenerProfile profile) {
		String username = profile.getUser() == null ? null : profile.getUser().getUsername();
		if (profile.isGhost()) {
			return notBlank(username) ? username : "Kullanici";
		}
		return notBlank(profile.getName())
				? profile.getName().trim()
				: (notBlank(username) ? username : "Kullanici");
	}

	private ListenerVisibilityMode publicVisibilityMode(ListenerVisibilityMode mode) {
		return mode == ListenerVisibilityMode.GHOST ? ListenerVisibilityMode.GHOST : null;
	}
	
	private String resolveMediaUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getDisplayUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("[resolver] listener media lookup failed mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
	
	private boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}
}
