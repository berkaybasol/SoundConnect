package com.berkayb.soundconnect.modules.collab.mapper;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabCreateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUpdateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.mapstruct.*;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CollabMapper
 *
 * Collab modulunde DTO ↔ Entity donusumlerini yonetir.
 * MapStruct kullanilarak yazildigi icin:
 * - Performans yuksektir (reflection yok)
 * - Test etmesi kolaydir
 * - Elasticsearch index dokumanina gecis oldugunda neredeyse hic degismez.
 */
@Mapper(componentModel = "spring")
public interface CollabMapper {
	
	/**
	 * Create DTO → Collab Entity
	 *
	 * Dikkat:
	 * - owner ve ownerRole DTO'dan gelmez → service katmaninda set edilir.
	 * - requiredInstruments ve city burada set edilmez → service katmaninde resolve edilir.
	 */
	@BeanMapping(ignoreByDefault = true)
	@Mapping(target = "title", source = "dto.title")
	@Mapping(target = "description", source = "dto.description")
	@Mapping(target = "category", source = "dto.category")
	@Mapping(target = "targetRoles", source = "dto.targetRoles")
	@Mapping(target = "price", source = "dto.price")
	@Mapping(target = "daily", source = "dto.daily")
	@Mapping(target = "expirationTime", source = "dto.expirationTime")
	Collab toEntity(CollabCreateRequestDto dto);
	
	
	/**
	 * Update DTO → Entity (PUT mantigi)
	 *
	 * owner, ownerRole, requiredInstruments, filledInstruments,
	 * createdAt, updatedAt gibi alanlar ignore edilir.
	 *
	 * requiredInstruments ve city service katmaninda set edilecek.
	 */
	@BeanMapping(ignoreByDefault = true)
	@Mapping(target = "title", source = "dto.title")
	@Mapping(target = "description", source = "dto.description")
	@Mapping(target = "category", source = "dto.category")
	@Mapping(target = "targetRoles", source = "dto.targetRoles")
	@Mapping(target = "price", source = "dto.price")
	@Mapping(target = "daily", source = "dto.daily")
	@Mapping(target = "expirationTime", source = "dto.expirationTime")
	void updateEntity(@MappingTarget Collab collab, CollabUpdateRequestDto dto);
	
	
	/**
	 * Collab Entity → Response DTO
	 *
	 * FE tarafina giden tum bilgiler burada map edilir.
	 */
	@BeanMapping(ignoreByDefault = true)
	@Mapping(target = "id", source = "id")
	@Mapping(target = "ownerId", source = "owner.id")
	@Mapping(target = "ownerRole", source = "ownerRole")
	@Mapping(target = "targetRoles", source = "targetRoles")
	@Mapping(target = "category", source = "category")
	@Mapping(target = "title", source = "title")
	@Mapping(target = "description", source = "description")
	@Mapping(target = "price", source = "price")
	@Mapping(target = "daily", source = "daily")
	@Mapping(target = "expirationTime", source = "expirationTime")
	@Mapping(target = "cityId", source = "city.id")
	@Mapping(target = "cityName", source = "city.name")
	@Mapping(target = "requiredInstrumentIds", expression = "java(mapInstrumentIds(collab.getRequiredInstruments()))")
	@Mapping(target = "filledInstrumentIds", expression = "java(mapInstrumentIds(collab.getFilledInstruments()))")
	@Mapping(target = "hasOpenSlots", expression = "java(hasOpenSlots(collab))")
	CollabResponseDto toResponseDto(Collab collab);
	
	
	// ----------------- HELPER METHODS --------------------
	
	default Set<UUID> mapInstrumentIds(Set<Instrument> instruments) {
		if (instruments == null) return Set.of();
		return instruments.stream()
		                  .map(Instrument::getId)
		                  .collect(Collectors.toSet());
	}
	
	default boolean hasOpenSlots(Collab collab) {
		if (collab.getRequiredInstruments() == null) return false;
		return collab.getFilledInstruments().size() < collab.getRequiredInstruments().size();
	}
}