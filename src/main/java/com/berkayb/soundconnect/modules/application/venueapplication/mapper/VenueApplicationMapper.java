package com.berkayb.soundconnect.modules.application.venueapplication.mapper;

import com.berkayb.soundconnect.modules.application.venueapplication.dto.request.VenueApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VenueApplicationMapper {
	@Mapping(target = "id", source = "id")
	@Mapping(target = "applicantUsername", source = "applicant.username")
	@Mapping(target = "venueName", source = "venueName")
	@Mapping(target = "venueAddress", source = "venueAddress")
	@Mapping(target = "phone", source = "applicant.phone")
	@Mapping(target = "status", source = "status")
	@Mapping(target = "applicationDate", source = "applicationDate")
	@Mapping(target = "decisionDate", source = "decisionDate")
	VenueApplicationResponseDto toResponseDto(VenueApplication venueApplication);
	
	// request'ten entity'ye map; applicant, status, date service'te setlenecek
	@Mapping(target = "id", ignore = true)
	@Mapping(target = "applicant", ignore = true)
	@Mapping(target = "status", ignore = true)
	@Mapping(target = "applicationDate", ignore = true)
	@Mapping(target = "decisionDate", ignore = true)
	VenueApplication toEntity(VenueApplicationCreateRequestDto dto);
}