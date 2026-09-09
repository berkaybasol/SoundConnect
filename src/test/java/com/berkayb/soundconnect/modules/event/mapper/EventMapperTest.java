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
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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

	@Test
	void listResolvesRepeatedPostersOnceAndPreservesOrderLegacyUrlsAndMissingMedia() {
		UUID ready = UUID.randomUUID(), hidden = UUID.randomUUID();
		List<Event> events = List.of(posterEvent(ready.toString()), posterEvent(ready.toString()),
				posterEvent(" /legacy/poster.jpg "), posterEvent(hidden.toString()), posterEvent(null));
		when(mediaAssetService.getDisplayUrlMap(List.of(ready, hidden))).thenReturn(Map.of(ready, "https://cdn.test/ready.jpg"));

		var result = mapper.toDtos(events);

		assertThat(result).extracting(EventResponseDto::id).containsExactlyElementsOf(events.stream().map(Event::getId).toList());
		assertThat(result).extracting(EventResponseDto::posterImage).containsExactly("https://cdn.test/ready.jpg",
				"https://cdn.test/ready.jpg", " /legacy/poster.jpg ", null, null);
		verify(mediaAssetService).getDisplayUrlMap(List.of(ready, hidden));
		verifyNoMoreInteractions(mediaAssetService);
	}

	@Test
	void largeLegacyListUsesBoundedMediaBatchesAndAnUnavailablePosterBatchDoesNotFailTheList() {
		List<Event> events = IntStream.range(0, 201).mapToObj(index -> posterEvent(UUID.randomUUID().toString())).toList();
		List<UUID> firstBatch = events.subList(0, 200).stream().map(event -> UUID.fromString(event.getPosterImage())).toList();
		UUID last = UUID.fromString(events.getLast().getPosterImage());
		when(mediaAssetService.getDisplayUrlMap(firstBatch)).thenThrow(new IllegalStateException("Media unavailable"));
		when(mediaAssetService.getDisplayUrlMap(List.of(last))).thenReturn(Map.of(last, "https://cdn.test/last.jpg"));

		var result = mapper.toDtos(events);

		assertThat(result).hasSize(201);
		assertThat(result.subList(0, 200)).allSatisfy(event -> assertThat(event.posterImage()).isNull());
		assertThat(result.getLast().posterImage()).isEqualTo("https://cdn.test/last.jpg");
		verify(mediaAssetService).getDisplayUrlMap(firstBatch);
		verify(mediaAssetService).getDisplayUrlMap(List.of(last));
		verifyNoMoreInteractions(mediaAssetService);
	}

	@Test
	void listsWithoutUuidPostersDoNotQueryMedia() {
		assertThat(mapper.toDtos(List.of())).isEmpty();
		assertThat(mapper.toDtos(List.of(posterEvent("/legacy/poster.jpg"), posterEvent(" ")))).hasSize(2);
		verifyNoInteractions(mediaAssetService);
	}

	private Event posterEvent(String poster) {
		Event event = Event.builder().title("Concert").posterImage(poster).build();
		event.setId(UUID.randomUUID());
		return event;
	}
}
