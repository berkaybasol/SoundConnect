package com.berkayb.soundconnect.modules.tablegroup.mapper;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupParticipantDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class TableGroupMapperTest {
	
	private final TableGroupMapper mapper = Mappers.getMapper(TableGroupMapper.class);
	
	@Test
	void toDto_shouldMapEntityToResponseDto_withLocationsAndParticipants() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID ownerId      = UUID.randomUUID();
		UUID venueId      = UUID.randomUUID();
		
		// location entities (entity field isimleri seninkine göre ufak fark gösterebilir;
		// ama en azından id + name set ediyoruz)
		UUID cityId = UUID.randomUUID();
		City city = new City();
		city.setId(cityId);
		city.setName("Ankara");
		
		UUID districtId = UUID.randomUUID();
		District district = new District();
		district.setId(districtId);
		district.setName("Çankaya");
		district.setCity(city);
		
		UUID neighborhoodId = UUID.randomUUID();
		Neighborhood neighborhood = new Neighborhood();
		neighborhood.setId(neighborhoodId);
		neighborhood.setName("Tunalı");
		neighborhood.setDistrict(district);
		
		// participants
		UUID participantUserId = UUID.randomUUID();
		Instant joinedAt = Instant.now().minusSeconds(600);
		
		TableGroupParticipant participant = TableGroupParticipant.builder()
		                                                         .userId(participantUserId)
		                                                         .joinedAt(joinedAt)
		                                                         .status(ParticipantStatus.ACCEPTED)
		                                                         .build();
		
		Set<TableGroupParticipant> participants = new HashSet<>();
		participants.add(participant);
		
		List<String> genderPrefs = new ArrayList<>();
		genderPrefs.add("MALE");
		genderPrefs.add("FEMALE");
		
		Instant startAt = Instant.now();
		Instant meetingAt = startAt.plusSeconds(3600);
		Instant expiresAt = Instant.now().plusSeconds(7200);
		
		TableGroup entity = TableGroup.builder()
		                              .ownerId(ownerId)
		                              .venueId(venueId)
		                              .venueName("Sound Bar")
		                              .description("Akustik müzik ve sohbet")
		                              .maxPersonCount(4)
		                              .genderPrefs(genderPrefs)
		                              .ageMin(20)
		                              .ageMax(30)
		                              .startAt(startAt)
		                              .meetingAt(meetingAt)
		                              .expiresAt(expiresAt)
		                              .status(TableGroupStatus.ACTIVE)
		                              .participants(participants)
		                              .build();
		
		entity.setId(tableGroupId);
		entity.setCity(city);
		entity.setDistrict(district);
		entity.setNeighborhood(neighborhood);
		
		// when
		TableGroupResponseDto dto = mapper.toDto(entity);
		
		// then
		assertThat(dto).isNotNull();
		assertThat(dto.id()).isEqualTo(tableGroupId);
		assertThat(dto.ownerId()).isEqualTo(ownerId);
		assertThat(dto.venueId()).isEqualTo(venueId);
		assertThat(dto.venueName()).isEqualTo("Sound Bar");
		assertThat(dto.description()).isEqualTo("Akustik müzik ve sohbet");
		assertThat(dto.maxPersonCount()).isEqualTo(4);
		assertThat(dto.genderPrefs()).containsExactly("MALE", "FEMALE");
		assertThat(dto.ageMin()).isEqualTo(20);
		assertThat(dto.ageMax()).isEqualTo(30);
		assertThat(dto.startAt()).isEqualTo(startAt);
		assertThat(dto.meetingAt()).isEqualTo(meetingAt);
		assertThat(dto.expiresAt()).isEqualTo(expiresAt);
		assertThat(dto.status()).isEqualTo(TableGroupStatus.ACTIVE);
		
		// participants mapping
		assertThat(dto.participants())
				.hasSize(1);
		TableGroupParticipantDto participantDto = dto.participants().iterator().next();
		assertThat(participantDto.userId()).isEqualTo(participantUserId);
		assertThat(participantDto.joinedAt()).isEqualTo(joinedAt);
		assertThat(participantDto.status()).isEqualTo(ParticipantStatus.ACCEPTED);
		
		// location mapping (helper toLocationDto'lar)
		assertThat(dto.city()).isNotNull();
		assertThat(dto.city().id()).isEqualTo(cityId);
		assertThat(dto.city().name()).isEqualTo("Ankara");
		
		assertThat(dto.district()).isNotNull();
		assertThat(dto.district().id()).isEqualTo(districtId);
		assertThat(dto.district().name()).isEqualTo("Çankaya");
		
		assertThat(dto.neighborhood()).isNotNull();
		assertThat(dto.neighborhood().id()).isEqualTo(neighborhoodId);
		assertThat(dto.neighborhood().name()).isEqualTo("Tunalı");
	}
	
	@Test
	void toEntity_shouldMapCreateRequestDto_andIgnoreLocationAndParticipants() {
		// given
		UUID cityId = UUID.randomUUID();
		UUID districtId = UUID.randomUUID();
		UUID neighborhoodId = UUID.randomUUID();
		
		List<String> genderPrefs = List.of("MALE", "FEMALE");
		Instant meetingAt = Instant.now().plusSeconds(10800);
		
		TableGroupCreateRequestDto requestDto = new TableGroupCreateRequestDto(
				UUID.randomUUID(),           // venueId
				"My Venue",                  // venueName
				"Table description",         // description
				4,                           // maxPersonCount
				genderPrefs,                 // gender prefs
				22,                          // ageMin
				30,                          // ageMax
				meetingAt,                   // meetingAt
				cityId,
				districtId,
				neighborhoodId
		);
		
		// when
		TableGroup entity = mapper.toEntity(requestDto);
		
		// then
		assertThat(entity).isNotNull();
		assertThat(entity.getVenueId()).isEqualTo(requestDto.venueId());
		assertThat(entity.getVenueName()).isEqualTo("My Venue");
		assertThat(entity.getDescription()).isEqualTo("Table description");
		assertThat(entity.getMaxPersonCount()).isEqualTo(4);
		assertThat(entity.getGenderPrefs()).containsExactlyElementsOf(genderPrefs);
		assertThat(entity.getAgeMin()).isEqualTo(22);
		assertThat(entity.getAgeMax()).isEqualTo(30);
		assertThat(entity.getMeetingAt()).isEqualTo(meetingAt);
		assertThat(entity.getExpiresAt()).isNull();
		
		// service layer set edecek: mapper ignore ediyor mu?
		assertThat(entity.getCity()).isNull();
		assertThat(entity.getDistrict()).isNull();
		assertThat(entity.getNeighborhood()).isNull();
		
		// participants da ignore edilmeli (service owner'ı ekliyor)
		// Lombok @Builder.Default sayesinde boş set olabilir; null da olabilir.
		assertThat(entity.getParticipants()).isNullOrEmpty();
	}

	@Test
	void optionalVenueFields_shouldRemainNullAcrossRequestEntityAndResponseMapping() {
		TableGroupCreateRequestDto requestDto = new TableGroupCreateRequestDto(
				null,
				null,
				"Mekan belirtilmeyen masa",
				2,
				List.of("MALE", "FEMALE"),
				22,
				30,
				Instant.now().plusSeconds(3600),
				UUID.randomUUID(),
				null,
				null
		);

		TableGroup entity = mapper.toEntity(requestDto);
		entity.setParticipants(new HashSet<>());
		TableGroupResponseDto response = mapper.toDto(entity);

		assertThat(entity.getVenueId()).isNull();
		assertThat(entity.getVenueName()).isNull();
		assertThat(response.venueId()).isNull();
		assertThat(response.venueName()).isNull();
	}
}
