package com.berkayb.soundconnect.modules.location.mapper;

import com.berkayb.soundconnect.modules.location.dto.request.DistrictRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.DistrictResponseDto;
import com.berkayb.soundconnect.modules.location.entity.District;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DistrictMapper {
	
	@Mapping(target = "city.id", source = "cityId") // sadece ID atanacak
	District toEntity(DistrictRequestDto dto);
	
	@Mapping(target = "cityId", source = "city.id") // sadece ID alınacak
	DistrictResponseDto toResponse(District district);
	
	List<DistrictResponseDto> toResponseList(List<District> districtList);
}