package com.berkayb.soundconnect.modules.instrument.mapper;

import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InstrumentMapperTest {
	
	// Eğer componentModel = "spring" kullanacaksan, bu sınıfı @SpringBootTest ile açıp bean enjekte edebilirsin.
	// Saf MapStruct testi için Mappers.getMapper yeterlidir.
	private final InstrumentMapper mapper = Mappers.getMapper(InstrumentMapper.class);
	
	@Test
	void toInstrument_shouldMapSaveDtoToEntity() {
		var dto = new InstrumentSaveRequestDto("Guitar");
		Instrument entity = mapper.toInstrument(dto);
		
		assertThat(entity).isNotNull();
		assertThat(entity.getName()).isEqualTo("Guitar");
	}
	
	@Test
	void toInstrumentResponseDto_shouldMapEntityToResponse() {
		Instrument entity = Instrument.builder()
		                              .name("Piano")
		                              .build();
		// BaseEntity id’sini simüle edelim:
		var id = UUID.randomUUID();
		entity.setId(id);
		
		InstrumentResponseDto resp = mapper.toInstrumentResponseDto(entity);
		
		assertThat(resp).isNotNull();
		// DTO id tipin seçeneğe göre değişir:
		// Eğer UUID yaptıysan:
		// assertThat(resp.id()).isEqualTo(id);
		// Eğer String bıraktıysan:
		// assertThat(resp.id()).isEqualTo(id.toString());
		assertThat(resp.name()).isEqualTo("Piano");
	}
}