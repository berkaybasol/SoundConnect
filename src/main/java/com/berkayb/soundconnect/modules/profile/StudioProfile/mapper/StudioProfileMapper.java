package com.berkayb.soundconnect.modules.profile.StudioProfile.mapper;

import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface StudioProfileMapper {
	
	StudioProfileResponseDto toDto(StudioProfile studioProfile);
}