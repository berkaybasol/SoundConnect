package com.berkayb.soundconnect.modules.event.mapper;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class EventMapperTest {
	
	@Mock
	private MediaAssetService mediaAssetService;

	@Mock
	private EventShareUrlBuilder eventShareUrlBuilder;

	@InjectMocks
	private EventMapper mapper;

	@Test
	void unspecifiedPerformerDoesNotPromiseAnAnnouncement() {
		Event event = Event.builder().title("Etkinlik")
				.eventDate(LocalDate.of(2026, 9, 8)).startTime(LocalTime.of(20, 0))
				.build();
		EventResponseDto dto = mapper.toDto(event);
		assertThat(dto.performerName()).isEqualTo("Belirtilmemiş");
		assertThat(dto.musicianProfileId()).isNull();
		assertThat(dto.bandId()).isNull();
	}
	
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

	@Test
	void toDto_shouldExposeBandIdForBandEvent() {
		UUID bandId = UUID.randomUUID();
		Band band = Band.builder()
		                .id(bandId)
		                .name("Sahbaz")
		                .build();
		Event event = Event.builder()
		                   .title("Band Night")
		                   .eventDate(LocalDate.now())
		                   .startTime(LocalTime.of(20, 0))
		                   .band(band)
		                   .build();

		EventResponseDto dto = mapper.toDto(event);

		assertThat(dto.bandId()).isEqualTo(bandId);
		assertThat(dto.musicianProfileId()).isNull();
		assertThat(dto.performerType()).isEqualTo(PerformerType.BAND);
		assertThat(dto.performerName()).isEqualTo("Sahbaz");
	}

	@Test
	void toDto_shouldExposeOnlyActiveBandMembers() {
		BandMember active = member("aktifuye", BandMemberShipStatus.ACTIVE);
		BandMember pending = member("bekleyenuye", BandMemberShipStatus.PENDING);
		BandMember rejected = member("reddedilenuye", BandMemberShipStatus.REJECTED);
		BandMember left = member("ayrilanuye", BandMemberShipStatus.LEFT);
		Band band = Band.builder()
		                .id(UUID.randomUUID())
		                .name("Sahbaz")
		                .members(Set.of(active, pending, rejected, left))
		                .build();
		Event event = Event.builder()
		                   .title("Band Night")
		                   .eventDate(LocalDate.now())
		                   .startTime(LocalTime.of(20, 0))
		                   .band(band)
		                   .build();

		EventResponseDto dto = mapper.toDto(event);

		assertThat(dto.bandMembers()).containsExactly("aktifuye");
	}

	private BandMember member(String username, BandMemberShipStatus status) {
		MusicianProfile profile = new MusicianProfile();
		User user = User.builder().username(username).build();
		user.setMusicianProfile(profile);
		profile.setUser(user);
		return BandMember.builder()
		                 .user(user)
		                 .status(status)
		                 .bandRole(BandRole.MEMBER)
		                 .build();
	}
}
