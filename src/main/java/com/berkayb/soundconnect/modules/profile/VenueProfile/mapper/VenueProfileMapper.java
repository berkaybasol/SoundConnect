package com.berkayb.soundconnect.modules.profile.VenueProfile.mapper;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VenueProfileMapper {
	
	// Entity → ResponseDto
	@Mapping(target = "venueId", source = "venue.id")
	@Mapping(target = "venueName", source = "venue.name")
	VenueProfileResponseDto toResponse(VenueProfile entity);
	
	// SaveRequestDto → Entity (VENUE ve BASEENTITY FIELD’LARI IGNORE ETME!)
	@Mapping(target = "venue", ignore = true)
	// AŞAĞIDAKİLERİ SİL!
	// @Mapping(target = "id", ignore = true)
	// @Mapping(target = "createdAt", ignore = true)
	// @Mapping(target = "updatedAt", ignore = true)
	VenueProfile toEntity(VenueProfileSaveRequestDto dto);
}