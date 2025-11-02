package com.berkayb.soundconnect.modules.tablegroup.mapper;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupParticipantDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import org.mapstruct.*;

import java.util.Set;

@Mapper(componentModel = "spring")
public interface TableGroupMapper {
	// Entity -> DTO
	@Mapping(target = "city", expression = "java(toLocationDto(entity.getCity()))")
	@Mapping(target = "district", expression = "java(toLocationDto(entity.getDistrict()))")
	@Mapping(target = "neighborhood", expression = "java(toLocationDto(entity.getNeighborhood()))")
	TableGroupResponseDto toDto(TableGroup entity);
	
	TableGroupParticipantDto toDto(TableGroupParticipant entity);
	
	Set<TableGroupParticipantDto> toParticipantDtoSet(Set<TableGroupParticipant> entities);
	
	// DTO -> Entity
	
	@Mapping(target = "city", ignore = true) // Bunlar genellikle service layer'da set edilir
	@Mapping(target = "district", ignore = true)
	@Mapping(target = "neighborhood", ignore = true)
	@Mapping(target = "participants", ignore = true) // Katılımcılar ayrı eklenir
	TableGroup toEntity(TableGroupCreateRequestDto dto);
	
	
	// Helper: Location entity -> LocationDto
	default TableGroupResponseDto.LocationDto toLocationDto(City city) {
		return city == null ? null : new TableGroupResponseDto.LocationDto(city.getId(), city.getName());
	}
	default TableGroupResponseDto.LocationDto toLocationDto(District district) {
		return district == null ? null : new TableGroupResponseDto.LocationDto(district.getId(), district.getName());
	}
	default TableGroupResponseDto.LocationDto toLocationDto(Neighborhood neighborhood) {
		return neighborhood == null ? null : new TableGroupResponseDto.LocationDto(neighborhood.getId(), neighborhood.getName());
	}
}