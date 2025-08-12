package com.berkayb.soundconnect.modules.location.mapper;

import com.berkayb.soundconnect.modules.location.dto.request.DistrictRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.DistrictResponseDto;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("mapper")
class DistrictMapperTest {
	
	private final DistrictMapper mapper = Mappers.getMapper(DistrictMapper.class);
	
	@Test
	void toEntity_setsOnlyCityId() {
		UUID cityId = UUID.randomUUID();
		DistrictRequestDto dto = new DistrictRequestDto("Çankaya", cityId);
		
		District entity = mapper.toEntity(dto);
		
		assertThat(entity.getName()).isEqualTo("Çankaya");
		assertThat(entity.getCity()).isNotNull();
		assertThat(entity.getCity().getId()).isEqualTo(cityId);
		// City’nin başka alanları map edilmez (sadece id atanır)
	}
	
	@Test
	void toResponse_flattensCityId() {
		City city = City.builder().build();
		city.setId(UUID.randomUUID());
		
		District district = District.builder().name("Keçiören").city(city).build();
		
		DistrictResponseDto resp = mapper.toResponse(district);
		assertThat(resp.name()).isEqualTo("Keçiören");
		assertThat(resp.cityId()).isEqualTo(city.getId());
	}
}