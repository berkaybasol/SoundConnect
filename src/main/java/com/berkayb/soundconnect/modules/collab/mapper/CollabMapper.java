package com.berkayb.soundconnect.modules.collab.mapper;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabCreateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUpdateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import org.mapstruct.*;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface CollabMapper {
	
	// --------------------------------------------------------
	// CREATE → ENTITY
	// --------------------------------------------------------
	@BeanMapping(ignoreByDefault = true)
	@Mapping(target = "title", source = "dto.title")
	@Mapping(target = "description", source = "dto.description")
	@Mapping(target = "category", source = "dto.category")
	@Mapping(target = "targetRoles", source = "dto.targetRoles")
	@Mapping(target = "price", source = "dto.price")
	@Mapping(target = "daily", source = "dto.daily")
	@Mapping(target = "expirationTime", source = "dto.expirationTime")
	Collab toEntity(CollabCreateRequestDto dto);
	
	
	// --------------------------------------------------------
	// UPDATE → MERGE
	// --------------------------------------------------------
	@BeanMapping(ignoreByDefault = true)
	@Mapping(target = "title", source = "dto.title")
	@Mapping(target = "description", source = "dto.description")
	@Mapping(target = "category", source = "dto.category")
	@Mapping(target = "targetRoles", source = "dto.targetRoles")
	@Mapping(target = "price", source = "dto.price")
	@Mapping(target = "daily", source = "dto.daily")
	@Mapping(target = "expirationTime", source = "dto.expirationTime")
	void updateEntity(@MappingTarget Collab collab, CollabUpdateRequestDto dto);
	
	
	// --------------------------------------------------------
	// ENTITY → RESPONSE DTO (UI-FRIENDLY)
	// --------------------------------------------------------
	@BeanMapping(ignoreByDefault = true)
	@Mapping(target = "id", source = "collab.id")
	@Mapping(target = "ownerId", source = "collab.owner.id")
	@Mapping(target = "ownerRole", source = "collab.ownerRole")
	@Mapping(target = "targetRoles", source = "collab.targetRoles")
	@Mapping(target = "category", source = "collab.category")
	@Mapping(target = "title", source = "collab.title")
	@Mapping(target = "description", source = "collab.description")
	@Mapping(target = "price", source = "collab.price")
	@Mapping(target = "daily", source = "collab.daily")
	@Mapping(target = "expirationTime", source = "collab.expirationTime")
	@Mapping(target = "cityId", source = "collab.city.id")
	@Mapping(target = "cityName", source = "collab.city.name")
	@Mapping(target = "requiredInstrumentIds",
			expression = "java(mapInstrumentIds(collab.getRequiredInstruments()))")
	@Mapping(target = "filledInstrumentIds",
			expression = "java(mapInstrumentIds(collab.getFilledInstruments()))")
	@Mapping(target = "hasOpenSlots",
			expression = "java(hasOpenSlots(collab))")
	
	// ----- UI FRIENDLY -----
	@Mapping(target = "isOwner",
			expression = "java(isOwner(collab, authenticatedUserId))")
	@Mapping(target = "isExpired",
			expression = "java(isExpired(collab))")
	@Mapping(target = "slotCount",
			expression = "java(calcSlotCount(collab))")
	
	CollabResponseDto toResponseDto(Collab collab,
	                                @Context UUID authenticatedUserId);
	
	
	// --------------------------------------------------------
	// HELPER METHODS
	// --------------------------------------------------------
	
	default Set<UUID> mapInstrumentIds(Set<Instrument> instruments) {
		if (instruments == null) return Set.of();
		return instruments.stream()
		                  .map(Instrument::getId)
		                  .collect(Collectors.toSet());
	}
	
	default boolean hasOpenSlots(Collab collab) {
		return collab.getFilledInstruments().size()
				< collab.getRequiredInstruments().size();
	}
	
	default boolean isOwner(Collab collab, UUID authenticatedUserId) {
		if (authenticatedUserId == null) return false;
		return collab.getOwner().getId().equals(authenticatedUserId);
	}
	
	default boolean isExpired(Collab collab) {
		if (!collab.isDaily()) return false;
		if (collab.getExpirationTime() == null) return false;
		return collab.getExpirationTime().isBefore(LocalDateTime.now());
	}
	
	default int calcSlotCount(Collab collab) {
		return collab.getRequiredInstruments().size()
				- collab.getFilledInstruments().size();
	}
	
	default CollabResponseDto toResponseDto(Collab collab) {
		return toResponseDto(collab, null);
	}
}