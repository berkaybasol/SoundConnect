package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class VenueProfileContributor implements PublicProfileContributor {
	
	private final VenueRepository venueRepository;
	private final VenueProfileRepository venueProfileRepository;
	private final MediaAssetService mediaAssetService;
	
	@Override
	public String type() {
		return "VENUE";
	}
	
	@Override
	public List<UserProfileTargetDto> resolve(UUID userId) {
		List<UserProfileTargetDto> result = new ArrayList<>();
		
		// Owner'a ait tüm mekanları bul
		List<Venue> venues = venueRepository.findAllByOwnerId(userId);
		
		for (Venue venue : venues) {
			// Venue'nin profile'ını getir
			VenueProfile vp = venueProfileRepository.findByVenueId(venue.getId()).orElse(null);
			if (vp == null) continue;
			
			// VenueProfile entity'sinde venueName yok, isim Venue entity'sinden alınır
			String displayName = notBlank(venue.getName()) ? venue.getName() : "Mekan";
			
			// Flutter route venue public profile'ı venueId ile açıyor
			result.add(new UserProfileTargetDto(
					type(),
					venue.getId(),
					displayName,
					resolveMediaUrl(vp.getProfilePictureMediaId())
			));
		}
		
		return result;
	}
	
	private String resolveMediaUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getDisplayUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("[resolver] venue media lookup failed mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
	
	private boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}
}
