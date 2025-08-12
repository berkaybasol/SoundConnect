package com.berkayb.soundconnect.modules.location.mapper;

import com.berkayb.soundconnect.modules.location.dto.request.NeighborhoodRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.NeighborhoodResponseDto;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("mapper")
class NeighborhoodMapperTest {
	
	private final NeighborhoodMapper mapper = Mappers.getMapper(NeighborhoodMapper.class);
	
	@Test
	void toEntity_setsOnlyDistrictId() {
		UUID districtId = UUID.randomUUID();
		NeighborhoodRequestDto dto = new NeighborhoodRequestDto("Bahçelievler", districtId);
		
		Neighborhood entity = mapper.toEntity(dto);
		
		assertThat(entity.getName()).isEqualTo("Bahçelievler");
		assertThat(entity.getDistrict()).isNotNull();
		assertThat(entity.getDistrict().getId()).isEqualTo(districtId);
	}
	
	@Test
	void toResponse_flattensDistrictId() {
		District district = District.builder().build();
		district.setId(UUID.randomUUID());
		Neighborhood n = Neighborhood.builder().name("Moda").district(district).build();
		
		NeighborhoodResponseDto resp = mapper.toResponse(n);
		assertThat(resp.name()).isEqualTo("Moda");
		assertThat(resp.districtId()).isEqualTo(district.getId());
	}
}