package com.berkayb.soundconnect.modules.event.mapper;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder; //eklendi
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class EventMapper {
	
	private final MediaAssetService mediaAssetService;
	private final EventShareUrlBuilder eventShareUrlBuilder; //eklendi
	
	public EventResponseDto toDto(Event event) {
		if (event == null) {
			return null;
		}
		
		return new EventResponseDto(
				event.getId(),
				event.getTitle(),
				resolvePosterImage(event),
				resolvePerformerName(event),
				event.getMusicianProfile() != null ? event.getMusicianProfile().getId() : null,
				resolvePerformerType(event),
				resolveBandMembers(event),
				event.getVenue() != null ? event.getVenue().getId() : null,
				event.getVenue() != null ? event.getVenue().getName() : null,
				event.getVenue() != null && event.getVenue().getCity() != null
						? event.getVenue().getCity().getName()
						: null,
				event.getVenue() != null && event.getVenue().getDistrict() != null
						? event.getVenue().getDistrict().getName()
						: null,
				event.getVenue() != null && event.getVenue().getNeighborhood() != null
						? event.getVenue().getNeighborhood().getName()
						: null,
				event.getEventDate(),
				event.getStartTime(),
				event.getEndTime(),
				event.getDescription(),
				eventShareUrlBuilder.buildEventShareUrl(event.getId()) //eklendi
		);
	}
	
	private String resolvePosterImage(Event event) {
		final String raw = event.getPosterImage();
		if (raw == null || raw.isBlank()) {
			return null;
		}
		
		final UUID assetId;
		try {
			assetId = UUID.fromString(raw);
		} catch (IllegalArgumentException ignored) {
			return raw;
		}
		try {
			return mediaAssetService.getDisplayUrl(assetId);
		} catch (Exception ignored) {
			return null;
		}
	}
	
	private String resolvePerformerName(Event event) {
		if (event.getBand() != null) {
			return event.getBand().getName();
		}
		
		if (event.getMusicianProfile() != null) {
			if (event.getMusicianProfile().getUser() != null &&
					event.getMusicianProfile().getUser().getUsername() != null &&
					!event.getMusicianProfile().getUser().getUsername().isBlank()) {
				return event.getMusicianProfile().getUser().getUsername();
			}
			
			if (event.getMusicianProfile().getStageName() != null &&
					!event.getMusicianProfile().getStageName().isBlank()) {
				return event.getMusicianProfile().getStageName();
			}
		}
		
		if (event.getManualPerformerName() != null &&
				!event.getManualPerformerName().isBlank()) {
			return event.getManualPerformerName();
		}
		
		return "Yakinda aciklanacak";
	}
	
	private PerformerType resolvePerformerType(Event event) {
		if (event.getBand() != null) {
			return PerformerType.BAND;
		}
		if (event.getMusicianProfile() != null) {
			return PerformerType.MUSICIAN;
		}
		if (event.getManualPerformerName() != null &&
				!event.getManualPerformerName().isBlank()) {
			return PerformerType.MANUAL;
		}
		return null;
	}
	
	private Set<String> resolveBandMembers(Event event) {
		if (event.getBand() == null || event.getBand().getMembers() == null) {
			return Set.of();
		}
		
		return event.getBand().getMembers().stream()
		            .map(BandMember::getUser)
		            .filter(user -> user.getMusicianProfile() != null)
		            .map(user -> {
			            final var profile = user.getMusicianProfile();
			            
			            if (profile.getUser() != null &&
					            profile.getUser().getUsername() != null &&
					            !profile.getUser().getUsername().isBlank()) {
				            return profile.getUser().getUsername();
			            }
			            
			            if (profile.getStageName() != null &&
					            !profile.getStageName().isBlank()) {
				            return profile.getStageName();
			            }
			            
			            return "Bilinmeyen uye";
		            })
		            .collect(Collectors.toSet());
	}
}
