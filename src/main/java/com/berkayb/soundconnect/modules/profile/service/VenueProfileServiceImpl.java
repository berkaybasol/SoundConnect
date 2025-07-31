package com.berkayb.soundconnect.modules.profile.service;

import com.berkayb.soundconnect.modules.profile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.mapper.VenueProfileMapper;
import com.berkayb.soundconnect.modules.profile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class VenueProfileServiceImpl implements VenueProfileService {
	private final VenueProfileRepository venueProfileRepository;
	private final VenueRepository venueRepository;
	private final VenueProfileMapper venueProfileMapper;
	private final VenueEntityFinder venueEntityFinder;
	
	@Override
	public VenueProfileResponseDto createProfile(UUID venueId, VenueProfileSaveRequestDto dto) {
		// venue getir.
		Venue venue = venueEntityFinder.getVenue(venueId);
		// daha once profil var mi kontrolu
		if (venueProfileRepository.findByVenueId(venueId).isPresent()) {
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		VenueProfile profile = VenueProfile.builder()
		                                   .venue(venue)
		                                   .bio(dto.bio())
		                                   .profilePicture(dto.profilePicture())
		                                   .instagramUrl(dto.instagramUrl())
		                                   .youtubeUrl(dto.youtubeUrl())
		                                   .websiteUrl(dto.websiteUrl())
		                                   .build();
		
		VenueProfile saved  = venueProfileRepository.save(profile);
		return venueProfileMapper.toResponse(saved);
	}
	
	
	@Override
	public VenueProfileResponseDto getProfileByVenueId(UUID venueId) {
		VenueProfile profile = venueProfileRepository.findByVenueId(venueId)
		                                             .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		return venueProfileMapper.toResponse(profile);
	}
	
	@Override
	public VenueProfileResponseDto updateProfile(UUID venueId, VenueProfileSaveRequestDto dto) {
		VenueProfile profile = venueProfileRepository.findByVenueId(venueId)
		                                             .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		
		
		if (dto.bio() != null) profile.setBio(dto.bio());
		if (dto.profilePicture() != null) profile.setProfilePicture(dto.profilePicture());
		if (dto.instagramUrl() != null) profile.setInstagramUrl(dto.instagramUrl());
		if (dto.youtubeUrl() != null) profile.setYoutubeUrl(dto.youtubeUrl());
		if (dto.websiteUrl() != null) profile.setWebsiteUrl(dto.websiteUrl());
		VenueProfile updated = venueProfileRepository.save(profile);
		return venueProfileMapper.toResponse(updated);
		
	}
}