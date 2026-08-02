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
	@Mapping(target = "cityId", source = "city.id")
	@Mapping(target = "cityName", source = "city.name")
	@Mapping(target = "districtId", source = "district.id")
	@Mapping(target = "districtName", source = "district.name")
	@Mapping(target = "neighborhoodId", source = "neighborhood.id")
	@Mapping(target = "neighborhoodName", source = "neighborhood.name")
	@Mapping(target = "activeRoomCount", ignore = true)
	@Mapping(target = "backlineUnitCount", ignore = true)
	StudioProfileResponseDto toDto(StudioProfile studioProfile);
}
