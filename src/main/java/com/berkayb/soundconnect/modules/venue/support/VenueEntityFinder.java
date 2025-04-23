package com.berkayb.soundconnect.modules.venue.support;

import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class VenueEntityFinder {
	
	private final VenueRepository venueRepository;
	
	// venue id ile veriyi getirir, bulunamazsa hata firlatir
	public Venue getVenue(UUID id) {
		return venueRepository.findById(id)
		                      .orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_NOT_FOUND));
	}
	
}