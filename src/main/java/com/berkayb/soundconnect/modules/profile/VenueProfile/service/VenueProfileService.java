package com.berkayb.soundconnect.modules.profile.VenueProfile.service;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueProfileResponseDto;

import java.util.UUID;

public interface VenueProfileService {
VenueProfileResponseDto createProfile (UUID venueId, VenueProfileSaveRequestDto dto);
VenueProfileResponseDto getProfileByVenueId (UUID venueId);
VenueProfileResponseDto updateProfile (UUID venueId, VenueProfileSaveRequestDto dto);
}