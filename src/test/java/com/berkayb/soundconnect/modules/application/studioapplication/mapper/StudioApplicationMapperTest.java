package com.berkayb.soundconnect.modules.application.studioapplication.mapper;

import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StudioApplicationMapperTest {
	private final StudioApplicationMapper mapper = Mappers.getMapper(StudioApplicationMapper.class);

	@Test
	void exposesUtcWallClockAsAnOffsetBearingInstant() throws Exception {
		City city = City.builder().id(UUID.randomUUID()).name("Istanbul").build();
		District district = District.builder().id(UUID.randomUUID()).name("Kadikoy").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder()
				.id(UUID.randomUUID()).name("Moda").district(district).build();
		StudioApplication application = StudioApplication.builder()
				.id(UUID.randomUUID())
				.applicant(User.builder().id(UUID.randomUUID()).username("owner").build())
				.studioName("Studio")
				.studioAddress("Address")
				.phone("05551234567")
				.city(city)
				.district(district)
				.neighborhood(neighborhood)
				.status(ApplicationStatus.PENDING)
				.applicationDate(LocalDateTime.of(2026, 8, 3, 9, 15))
				.build();

		var response = mapper.toResponseDto(application);

		assertThat(response.applicationDate()).isEqualTo(Instant.parse("2026-08-03T09:15:00Z"));
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		assertThat(objectMapper.writeValueAsString(response))
				.contains("\"applicationDate\":\"2026-08-03T09:15:00Z\"");
	}
}
