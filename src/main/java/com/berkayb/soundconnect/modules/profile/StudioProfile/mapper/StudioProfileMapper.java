package com.berkayb.soundconnect.modules.profile.StudioProfile.mapper;

import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface StudioProfileMapper {
	
	@Mapping(target = "userId", source = "user.id")
	@Mapping(target = "profilePictureUrl", ignore = true)
	@Mapping(target = "adress", source = "address")
	StudioProfileResponseDto toDto(StudioProfile studioProfile);
}
