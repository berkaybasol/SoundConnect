package com.berkayb.soundconnect.location.mapper;

import com.berkayb.soundconnect.location.dto.request.NeighborhoodRequestDto;
import com.berkayb.soundconnect.location.dto.response.NeighborhoodResponseDto;
import com.berkayb.soundconnect.location.entity.Neighborhood;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface NeighborhoodMapper {
	
	@Mapping(target = "district.id", source = "districtId")
	Neighborhood toEntity(NeighborhoodRequestDto dto);
	
	@Mapping(target = "districtId", source = "district.id")
	NeighborhoodResponseDto toResponse(Neighborhood neighborhood);
	
	List<NeighborhoodResponseDto> toResponseList(List<Neighborhood> neighborhoods);
}