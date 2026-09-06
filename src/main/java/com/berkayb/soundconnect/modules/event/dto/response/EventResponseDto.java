package com.berkayb.soundconnect.modules.event.dto.response;

import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.enums.EventVenueApprovalStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

public record EventResponseDto(
		UUID id,
		
		String title, // event karti ve detay ekrani icin baslik
		String posterImage, // event afis gorseli
		
		String performerName,      // musician.stageName veya band.name
		UUID musicianProfileId,
		UUID bandId,
		PerformerType performerType,  // MUSICIAN / BAND
		
		Set<String> bandMembers,   // yalnızca band ise dolu
		
		UUID venueId,
		String venueName,
		String venueCity,
		String venueDistrict,
		String venueNeighborhood,
		
		LocalDate eventDate,
		LocalTime startTime,
		LocalTime endTime,
		String description,
		String shareUrl,
		EventOrigin eventOrigin,
		EventVenueApprovalStatus venueApprovalStatus,
		boolean venueCalendarApproved
) {
	public EventResponseDto(UUID id, String title, String posterImage, String performerName,
			UUID musicianProfileId, UUID bandId, PerformerType performerType, Set<String> bandMembers,
			UUID venueId, String venueName, String venueCity, String venueDistrict, String venueNeighborhood,
			LocalDate eventDate, LocalTime startTime, LocalTime endTime, String description, String shareUrl) {
		this(id, title, posterImage, performerName, musicianProfileId, bandId, performerType, bandMembers,
				venueId, venueName, venueCity, venueDistrict, venueNeighborhood, eventDate, startTime, endTime,
				description, shareUrl, EventOrigin.VENUE, EventVenueApprovalStatus.APPROVED, true);
	}
}
