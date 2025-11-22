package com.berkayb.soundconnect.modules.collab.mapper;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabCreateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUpdateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.RequiredSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.dto.response.SlotResponseDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabRequiredSlot;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import org.mapstruct.*;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface CollabMapper {
	
	// ---------------------------------------------
	// CREATE → ENTITY + SLOT PRODUKSIYONU
	// ---------------------------------------------
	@BeanMapping(ignoreByDefault = true)
	@Mapping(target = "title",          source = "dto.title")
	@Mapping(target = "description",    source = "dto.description")
	@Mapping(target = "category",       source = "dto.category")
	@Mapping(target = "targetRoles",    source = "dto.targetRoles")
	@Mapping(target = "price",          source = "dto.price")
	@Mapping(target = "daily",          source = "dto.daily")
	@Mapping(target = "expirationTime", source = "dto.expirationTime")
	Collab toEntity(CollabCreateRequestDto dto);
	
	
	// ---------------------------------------------
	// UPDATE → ENTITY FIELD MERGE (slot merge ayrı)
	// ---------------------------------------------
	@BeanMapping(ignoreByDefault = true)
	@Mapping(target = "title",          source = "dto.title")
	@Mapping(target = "description",    source = "dto.description")
	@Mapping(target = "category",       source = "dto.category")
	@Mapping(target = "targetRoles",    source = "dto.targetRoles")
	@Mapping(target = "price",          source = "dto.price")
	@Mapping(target = "daily",          source = "dto.daily")
	@Mapping(target = "expirationTime", source = "dto.expirationTime")
	void updateEntity(@MappingTarget Collab collab, CollabUpdateRequestDto dto);
	
	
	// ---------------------------------------------
	// ENTITY → RESPONSE DTO
	// ---------------------------------------------
	@BeanMapping(ignoreByDefault = true)
	@Mapping(target = "id",               source = "collab.id")
	@Mapping(target = "ownerId",          source = "collab.owner.id")
	@Mapping(target = "ownerRole",        source = "collab.ownerRole")
	@Mapping(target = "targetRoles",      source = "collab.targetRoles")
	@Mapping(target = "category",         source = "collab.category")
	@Mapping(target = "title",            source = "collab.title")
	@Mapping(target = "description",      source = "collab.description")
	@Mapping(target = "price",            source = "collab.price")
	@Mapping(target = "daily",            source = "collab.daily")
	@Mapping(target = "expirationTime",   source = "collab.expirationTime")
	@Mapping(target = "cityId",           source = "collab.city.id")
	@Mapping(target = "cityName",         source = "collab.city.name")
	@Mapping(target = "isOwner",
			expression = "java(isOwner(collab, authenticatedUserId))")
	@Mapping(target = "isExpired",
			expression = "java(isExpired(collab))")
	@Mapping(target = "hasOpenSlots",
			expression = "java(collab.hasOpenSlots())")
	@Mapping(target = "slotCount",
			expression = "java(collab.getRemainingSlotCount())")
	@Mapping(target = "requiredInstrumentIds",
			expression = "java(extractInstrumentIds(collab))")
	@Mapping(target = "filledInstrumentIds",
			expression = "java(extractFilledInstrumentIds(collab))")
	@Mapping(target = "slots",
			expression = "java(mapSlots(collab))")
	CollabResponseDto toResponseDto(Collab collab, @Context UUID authenticatedUserId);
	
	default CollabResponseDto toResponseDto(Collab collab) {
		return toResponseDto(collab, null);
	}
	
	// ---------------------------------------------
	// HELPER METHODS
	// ---------------------------------------------
	
	/**
	 * Required slotlardan enstrüman ID’lerini çıkartır
	 * (UI için eski “requiredInstrumentIds” alanını korumak adına)
	 */
	default Set<UUID> extractInstrumentIds(Collab collab) {
		return collab.getRequiredSlots().stream()
		             .map(slot -> slot.getInstrument().getId())
		             .collect(Collectors.toSet());
	}
	
	/**
	 * Sadece “filledCount > 0” olan slotların enstrümanlarını döner
	 */
	default Set<UUID> extractFilledInstrumentIds(Collab collab) {
		return collab.getRequiredSlots().stream()
		             .filter(slot -> slot.getFilledCount() > 0)
		             .map(slot -> slot.getInstrument().getId())
		             .collect(Collectors.toSet());
	}
	
	default boolean isOwner(Collab collab, UUID userId) {
		if (userId == null) return false;
		return collab.getOwner().getId().equals(userId);
	}
	
	default Set<SlotResponseDto> mapSlots(Collab collab) {
		return collab.getRequiredSlots().stream()
		             .map(slot -> new SlotResponseDto(
				             slot.getInstrument().getId(),
				             slot.getInstrument().getName(),
				             slot.getRequiredCount(),
				             slot.getFilledCount(),
				             slot.hasOpenSlot()
		             ))
		             .collect(Collectors.toSet());
	}
	
	
	default boolean isExpired(Collab collab) {
		if (!collab.isDaily()) return false;
		if (collab.getExpirationTime() == null) return false;
		return collab.getExpirationTime().isBefore(java.time.LocalDateTime.now());
	}
}