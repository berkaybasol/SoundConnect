package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
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
	private final UserRepository userRepository;
	private final MediaAssetService mediaAssetService;
	
	@Override
	public String type() {
		return "LISTENER";
	}
	
	@Override
	public List<UserProfileTargetDto> resolve(UUID userId) {
		return listenerProfileRepository.findByUserId(userId)
		                                .map(lp -> List.of(
				                                new UserProfileTargetDto(
						                                type(),
						                                lp.getId(),
						                                resolveDisplayName(lp.getName(), userId),
						                                resolveMediaUrl(lp.getProfilePictureMediaId())
				                                )
		                                ))
		                                .orElse(List.of());
	}
	
	private String resolveDisplayName(String name, UUID userId) {
		if (notBlank(name)) return name;
		return userRepository.findById(userId).map(User::getUsername).orElse("Kullanici");
	}
	
	private String resolveMediaUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getPlaybackUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("[resolver] listener media lookup failed mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
	
	private boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}
}