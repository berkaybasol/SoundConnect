package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.mapper;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ArtistVenueConnectionRequestMapper {
	
	@Mapping(target = "musicianProfileId", source = "musicianProfile.id")
	@Mapping(target = "musicianStageName", source = "musicianProfile.stageName")
	@Mapping(target = "venueId", source = "venue.id")
	@Mapping(target = "venueName", source = "venue.name")
	@Mapping(target = "status", source = "status") // Enum string olarak döner zaten
	@Mapping(target = "requestByType", source = "requestByType")
	@Mapping(target = "message", source = "message")
	@Mapping(target = "createdAt", source = "createdAt")
	ArtistVenueConnectionRequestResponseDto toResponseDto(ArtistVenueConnectionRequest entity);
}