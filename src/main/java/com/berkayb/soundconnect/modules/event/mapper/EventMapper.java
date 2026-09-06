package com.berkayb.soundconnect.modules.event.mapper;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder; //eklendi
import com.berkayb.soundconnect.modules.event.support.EventPosterResolver;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
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
		boolean publicVenue = event.getVenue() != null && (event.getEventOrigin() == EventOrigin.VENUE
				|| event.getVenue().getStatus() == VenueStatus.APPROVED);
		
		return new EventResponseDto(
				event.getId(),
				event.getTitle(),
				resolvePosterImage(event),
				resolvePerformerName(event),
				event.getMusicianProfile() != null ? event.getMusicianProfile().getId() : null,
				event.getBand() != null ? event.getBand().getId() : null,
				resolvePerformerType(event),
				resolveBandMembers(event),
				publicVenue ? event.getVenue().getId() : null,
				publicVenue ? event.getVenue().getName() : event.getVenueNameSnapshot(),
				publicVenue && event.getVenue().getCity() != null
						? event.getVenue().getCity().getName()
						: null,
				publicVenue && event.getVenue().getDistrict() != null
						? event.getVenue().getDistrict().getName()
						: null,
				publicVenue && event.getVenue().getNeighborhood() != null
						? event.getVenue().getNeighborhood().getName()
						: null,
				event.getEventDate(),
				event.getStartTime(),
				event.getEndTime(),
				event.getDescription(),
				eventShareUrlBuilder.buildEventShareUrl(event.getId()),
				event.getEventOrigin(),
				event.getVenueApprovalStatus(),
				event.isVenueCalendarApproved()
		);
	}
	
	private String resolvePosterImage(Event event) {
		return EventPosterResolver.resolve(event.getPosterImage(), mediaAssetService);
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
		
		return "Belirtilmemiş";
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
		            .filter(member -> member.getStatus() == BandMemberShipStatus.ACTIVE)
		            .map(BandMember::getUser)
		            .filter(java.util.Objects::nonNull)
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
