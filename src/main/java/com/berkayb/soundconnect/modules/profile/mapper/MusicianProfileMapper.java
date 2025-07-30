package com.berkayb.soundconnect.modules.profile.mapper;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.profile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface MusicianProfileMapper {
	@Mapping(target = "instruments", source = "instruments", qualifiedByName = "instrumentNames")
	@Mapping(target = "activeVenues", source = "activeVenues", qualifiedByName = "venueNames")
	MusicianProfileResponseDto toDto(MusicianProfile profile);
	
	@Named("instrumentNames")
	default Set<String> instrumentNames(Set<Instrument> instruments){
		return instruments == null ? null : instruments.stream().map(Instrument::getName).collect(Collectors.toSet());
	}
	
	@Named("venueNames")
	default Set<String> venueNames(Set<Venue> venues){
		return venues == null ? null : venues.stream().map(Venue::getName).collect(Collectors.toSet());
	}
}