package com.berkayb.soundconnect.modules.event.mapper;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventMapperTest {
	
	private final EventMapper mapper = Mappers.getMapper(EventMapper.class);
	
	@Test
	void toDto_shouldMapMusicianEventProperly() {
		var musician = new MusicianProfile();
		musician.setStageName("Berkay Başol");
		
		var venue = new Venue();
		venue.setId(UUID.randomUUID());
		venue.setName("IF Performance Hall");
		
		var event = Event.builder()
		                 
		                 .title("SoundConnect Launch")
		                 .description("Big concert")
		                 .eventDate(LocalDate.now())
		                 .startTime(LocalTime.of(20, 0))
		                 .endTime(LocalTime.of(22, 0))
		                 .venue(venue)
		                 .musicianProfile(musician)
		                 .build();
		
		EventResponseDto dto = mapper.toDto(event);
		
		assertThat(dto.performerType()).isEqualTo(PerformerType.MUSICIAN);
		assertThat(dto.performerName()).isEqualTo("Berkay Başol");
		assertThat(dto.venueName()).isEqualTo("IF Performance Hall");
		
	}
}