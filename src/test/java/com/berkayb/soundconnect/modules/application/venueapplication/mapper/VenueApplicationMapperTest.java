package com.berkayb.soundconnect.modules.application.venueapplication.mapper;

import com.berkayb.soundconnect.modules.application.venueapplication.dto.request.VenueApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VenueApplicationMapperTest {
	
	private final VenueApplicationMapper mapper = Mappers.getMapper(VenueApplicationMapper.class);
	
	@Test
	void toResponseDto_should_map_all_expected_fields() {
		// given
		UUID appId = UUID.randomUUID();
		User applicant = User.builder()
		                     .id(UUID.randomUUID())
		                     .username("ali_can")
		                     .phone("5550001111")
		                     .build();
		
		LocalDateTime now = LocalDateTime.now();
		
		VenueApplication entity = VenueApplication.builder()
		                                          .id(appId)
		                                          .applicant(applicant)
		                                          .venueName("Cool Venue")
		                                          .venueAddress("Some Address 123")
		                                          .status(ApplicationStatus.PENDING)
		                                          .applicationDate(now)
		                                          .decisionDate(null)
		                                          .build();
		
		// when
		VenueApplicationResponseDto dto = mapper.toResponseDto(entity);
		
		// then
		assertThat(dto.id()).isEqualTo(appId);
		assertThat(dto.applicantUsername()).isEqualTo("ali_can");
		assertThat(dto.phone()).isEqualTo("5550001111");
		assertThat(dto.venueName()).isEqualTo("Cool Venue");
		assertThat(dto.venueAddress()).isEqualTo("Some Address 123");
		assertThat(dto.status()).isEqualTo(ApplicationStatus.PENDING);
		assertThat(dto.applicationDate()).isEqualTo(now);
		assertThat(dto.decisionDate()).isNull();
	}
	
	@Test
	void toEntity_should_copy_simple_fields_and_leave_ignored_null() {
		// given
		String cityId = UUID.randomUUID().toString();
		String districtId = UUID.randomUUID().toString();
		String neighborhoodId = UUID.randomUUID().toString();
		
		VenueApplicationCreateRequestDto req = new VenueApplicationCreateRequestDto(
				"New Venue",
				"Addr 1",
				cityId,
				districtId,
				neighborhoodId
		);
		
		// when
		VenueApplication entity = mapper.toEntity(req);
		
		// then (maplenen basit alanlar)
		assertThat(entity.getVenueName()).isEqualTo("New Venue");
		assertThat(entity.getVenueAddress()).isEqualTo("Addr 1");
		
		// then (ignore edilenler null kalmalı - service setleyecek)
		assertThat(entity.getId()).isNull();
		assertThat(entity.getApplicant()).isNull();
		assertThat(entity.getCity()).isNull();
		assertThat(entity.getDistrict()).isNull();
		assertThat(entity.getNeighborhood()).isNull();
		assertThat(entity.getStatus()).isNull();
		assertThat(entity.getApplicationDate()).isNull();
		assertThat(entity.getDecisionDate()).isNull();
		assertThat(entity.getPhone()).isNull();
	}
}