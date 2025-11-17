package com.berkayb.soundconnect.modules.event.mapper;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface EventMapper {
	
	@Mapping(target = "performerName", source = "event", qualifiedByName = "resolvePerformerName")
	@Mapping(target = "performerType", source = "event", qualifiedByName = "resolvePerformerType")
	@Mapping(target = "bandMembers",   source = "event", qualifiedByName = "resolveBandMembers")
	
	@Mapping(target = "venueId",       source = "venue.id")
	@Mapping(target = "venueName",     source = "venue.name")
	@Mapping(target = "venueCity",     source = "venue.city.name")
	@Mapping(target = "venueDistrict", source = "venue.district.name")
	@Mapping(target = "venueNeighborhood", source = "venue.neighborhood.name")
	
	EventResponseDto toDto(Event event);
	
	
	/* -------------------------------------------------------
	 * Performer Name
	 * -------------------------------------------------------
	 */
	@Named("resolvePerformerName")
	default String resolvePerformerName(Event event) {
		if (event.getBand() != null) {
			return event.getBand().getName();
		}
		if (event.getMusicianProfile() != null) {
			return event.getMusicianProfile().getStageName();
		}
		return null;
	}
	
	
	/* -------------------------------------------------------
	 * Performer Type
	 * -------------------------------------------------------
	 */
	@Named("resolvePerformerType")
	default PerformerType resolvePerformerType(Event event) {
		if (event.getBand() != null) return PerformerType.BAND;
		return PerformerType.MUSICIAN;
	}
	
	
	/* -------------------------------------------------------
	 * Band Members (MusicianProfile isimlerini döner)
	 * -------------------------------------------------------
	 *
	 * Null-safe:
	 * - band yoksa boş set döner
	 * - bandMember null profil taşıyorsa filtrelenir
	 */
	@Named("resolveBandMembers")
	default Set<String> resolveBandMembers(Event event) {
		if (event.getBand() == null) return Set.of();
		
		return event.getBand().getMembers().stream()
		            .map(BandMember::getUser)
		            .filter(user -> user.getMusicianProfile() != null)
		            .map(user -> user.getMusicianProfile().getStageName())
		            .collect(Collectors.toSet());
	}
}