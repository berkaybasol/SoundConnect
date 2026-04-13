package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
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
public class StudioProfileContributor implements PublicProfileContributor {
	
	private final StudioProfileRepository studioProfileRepository;
	private final UserRepository userRepository;
	private final MediaAssetService mediaAssetService;
	
	@Override
	public String type() {
		return "STUDIO";
	}
	
	@Override
	public List<UserProfileTargetDto> resolve(UUID userId) {
		return studioProfileRepository.findByUserId(userId)
		                              .map(sp -> List.of(
				                              new UserProfileTargetDto(
						                              type(),
						                              sp.getId(),
						                              resolveDisplayName(sp.getName(), userId),
						                              resolveMediaUrl(sp.getProfilePictureMediaId())
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
			log.warn("[resolver] studio media lookup failed mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
	
	private boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}
}