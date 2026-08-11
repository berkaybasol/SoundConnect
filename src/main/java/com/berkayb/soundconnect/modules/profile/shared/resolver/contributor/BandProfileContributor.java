package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class BandProfileContributor implements PublicProfileContributor {

	private final BandRepresentationPolicy bandRepresentationPolicy;
	private final MediaAssetService mediaAssetService;

	@Override
	public String type() {
		return "BAND";
	}

	@Override
	public List<UserProfileTargetDto> resolve(UUID userId) {
		return bandRepresentationPolicy.findRepresentableBands(userId)
		                               .stream()
		                               .map(this::toTarget)
		                               .toList();
	}

	private UserProfileTargetDto toTarget(Band band) {
		return new UserProfileTargetDto(
				type(),
				band.getId(),
				resolveDisplayName(band.getName()),
				resolveMediaUrl(band.getProfilePictureMediaId())
		);
	}

	private String resolveDisplayName(String name) {
		return name == null || name.isBlank() ? "Grup" : name;
	}

	private String resolveMediaUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getDisplayUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("[resolver] band media lookup failed mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
}
