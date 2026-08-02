package com.berkayb.soundconnect.modules.application.studioapplication.mapper;

import com.berkayb.soundconnect.modules.application.studioapplication.dto.response.StudioApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface StudioApplicationMapper {
	@Mapping(target = "applicantId", source = "applicant.id")
	@Mapping(target = "applicantUsername", source = "applicant.username")
	@Mapping(target = "cityId", source = "city.id")
	@Mapping(target = "cityName", source = "city.name")
	@Mapping(target = "districtId", source = "district.id")
	@Mapping(target = "districtName", source = "district.name")
	@Mapping(target = "neighborhoodId", source = "neighborhood.id")
	@Mapping(target = "neighborhoodName", source = "neighborhood.name")
	@Mapping(target = "reviewedById", source = "reviewedBy.id")
	StudioApplicationResponseDto toResponseDto(StudioApplication application);
}
