package com.berkayb.soundconnect.modules.location.mapper;

import com.berkayb.soundconnect.modules.location.dto.request.CityRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.CityResponseDto;
import com.berkayb.soundconnect.modules.location.entity.City;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CityMapper {
	
	City toEntity(CityRequestDto dto);
	
	CityResponseDto toResponse(City city);
	
	List<CityResponseDto> toResponseList(List<City> cities);
}