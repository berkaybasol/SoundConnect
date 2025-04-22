package com.berkayb.soundconnect.venue.mapper;

import com.berkayb.soundconnect.location.entity.City;
import com.berkayb.soundconnect.location.entity.District;
import com.berkayb.soundconnect.location.entity.Neighborhood;
import com.berkayb.soundconnect.user.entity.User;
import com.berkayb.soundconnect.venue.dto.request.VenueRequestDto;
import com.berkayb.soundconnect.venue.dto.response.VenueResponseDto;
import com.berkayb.soundconnect.venue.entity.Venue;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface VenueMapper {
	
	// VenueRequestDto → Venue (entity)
	@Mapping(target = "id", ignore = true)
	@Mapping(target = "status", ignore = true) // Serviste set edeceğiz: default PENDING
	@Mapping(target = "city", source = "city")
	@Mapping(target = "district", source = "district")
	@Mapping(target = "neighborhood", source = "neighborhood")
	@Mapping(target = "owner", source = "owner")
	Venue toEntity(VenueRequestDto dto, City city, District district, Neighborhood neighborhood, User owner);
	
	// Venue → VenueResponseDto
	@Mapping(target = "cityName", source = "city.name")
	@Mapping(target = "districtName", source = "district.name")
	@Mapping(target = "neighborhoodName", source = "neighborhood.name")
	@Mapping(target = "ownerId", source = "owner.id")
	@Mapping(target = "ownerFullName", expression = "java(venue.getOwner().getFirstName() + \" \" + venue.getOwner().getLastName())")
	VenueResponseDto toResponse(Venue venue);
	
	List<VenueResponseDto> toResponseList(List<Venue> venues);
}