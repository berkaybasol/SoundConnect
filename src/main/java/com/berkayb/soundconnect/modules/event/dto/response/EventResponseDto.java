package com.berkayb.soundconnect.modules.event.dto.response;

import com.berkayb.soundconnect.modules.event.enums.PerformerType;

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
		String shareUrl
) {}