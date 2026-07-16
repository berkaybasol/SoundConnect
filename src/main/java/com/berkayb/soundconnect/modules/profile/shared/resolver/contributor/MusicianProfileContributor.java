package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
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
public class MusicianProfileContributor implements PublicProfileContributor {
	
	private final MusicianProfileRepository musicianProfileRepository;
	private final UserRepository userRepository;
	private final MediaAssetService mediaAssetService;
	
	@Override
	public String type() {
		return "MUSICIAN";
	}
	
	@Override
	public List<UserProfileTargetDto> resolve(UUID userId) {
		return musicianProfileRepository.findByUserId(userId)
		                                .map(mp -> List.of(
				                                new UserProfileTargetDto(
						                                type(),
						                                mp.getId(),
						                                resolveDisplayName(mp.getName(), mp.getStageName(), userId),
						                                resolveMediaUrl(mp.getProfilePictureMediaId())
				                                )
		                                ))
		                                .orElse(List.of());
	}
	
	private String resolveDisplayName(String name, String stageName, UUID userId) {
		if (notBlank(name)) return name;
		if (notBlank(stageName)) return stageName;
		return userRepository.findById(userId).map(User::getUsername).orElse("Kullanici");
	}
	
	private String resolveMediaUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getDisplayUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("[resolver] musician media lookup failed mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
	
	private boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}
}
