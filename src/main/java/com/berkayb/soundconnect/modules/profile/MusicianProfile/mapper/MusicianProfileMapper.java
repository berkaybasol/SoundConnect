package com.berkayb.soundconnect.modules.profile.MusicianProfile.mapper;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface MusicianProfileMapper {
	
	@Mapping(source = "description", target = "bio")
	@Mapping(target = "instruments", source = "instruments", qualifiedByName = "instrumentNames")
	@Mapping(target = "activeVenues", source = "activeVenues", qualifiedByName = "venueNames")
		// bands alanı service tarafında setlenecek → mapper buraya dokunmuyor
	MusicianProfileResponseDto toDto(MusicianProfile profile);
	
	@Named("instrumentNames")
	default Set<String> instrumentNames(Set<Instrument> instruments) {
		return instruments == null ? null :
				instruments.stream().map(Instrument::getName).collect(Collectors.toSet());
	}
	
	@Named("venueNames")
	default Set<String> venueNames(Set<Venue> venues) {
		return venues == null ? null :
				venues.stream().map(Venue::getName).collect(Collectors.toSet());
	}
}