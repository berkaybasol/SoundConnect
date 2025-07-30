package com.berkayb.soundconnect.modules.venue.mapper;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.dto.request.VenueRequestDto;
import com.berkayb.soundconnect.modules.venue.dto.response.VenueResponseDto;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface VenueMapper {
	
	@Mapping(target = "id", ignore = true)
	@Mapping(target = "status", ignore = true)
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "updatedAt", ignore = true)
	
	// DTO alanları
	@Mapping(target = "name", source = "dto.name")
	@Mapping(target = "address", source = "dto.address")
	@Mapping(target = "phone", source = "dto.phone")
	@Mapping(target = "website", source = "dto.website")
	@Mapping(target = "description", source = "dto.description")
	@Mapping(target = "musicStartTime", source = "dto.musicStartTime")
	
	
	// Diğer entity’ler
	@Mapping(target = "city", source = "city")
	@Mapping(target = "district", source = "district")
	@Mapping(target = "neighborhood", source = "neighborhood")
	@Mapping(target = "owner", source = "owner")
	Venue toEntity(VenueRequestDto dto, City city, District district, Neighborhood neighborhood, User owner);
	
	@Mapping(target = "cityName", source = "city.name")
	@Mapping(target = "districtName", source = "district.name")
	@Mapping(target = "neighborhoodName", source = "neighborhood.name")
	@Mapping(target = "ownerId", source = "owner.id")
	@Mapping(target = "ownerFullName", source = "owner.username")
	@Mapping(target = "activeMusicians", expression = "java(toActiveMusicians(venue))")
	VenueResponseDto toResponse(Venue venue);
	
	default Set<String> toActiveMusicians(Venue venue) {
		if (venue.getActiveMusicians() == null) return null;
		return venue.getActiveMusicians()
		            .stream()
		            .map(mp -> mp.getStageName())
		            .collect(java.util.stream.Collectors.toSet());
	}
	
	List<VenueResponseDto> toResponseList(List<Venue> venues);
}