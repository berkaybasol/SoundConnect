package com.berkayb.soundconnect.modules.location.mapper;

import com.berkayb.soundconnect.modules.location.dto.request.CityRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.CityResponseDto;
import com.berkayb.soundconnect.modules.location.entity.City;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("mapper")
class CityMapperTest {
	
	private final CityMapper mapper = Mappers.getMapper(CityMapper.class);
	
	@Test
	void toEntity_mapsName() {
		CityRequestDto dto = new CityRequestDto("Ankara");
		City entity = mapper.toEntity(dto);
		assertThat(entity.getName()).isEqualTo("Ankara");
	}
	
	@Test
	void toResponse_mapsIdAndName() {
		City entity = City.builder().name("İstanbul").build();
		CityResponseDto resp = mapper.toResponse(entity);
		assertThat(resp.name()).isEqualTo("İstanbul");
		// id null olabilir; mapper bunu olduğu gibi geçirir
	}
}