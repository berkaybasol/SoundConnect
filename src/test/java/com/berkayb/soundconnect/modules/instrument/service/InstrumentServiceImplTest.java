package com.berkayb.soundconnect.modules.instrument.service;

import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.mapper.InstrumentMapper;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class InstrumentServiceImplTest {
	
	@Mock
	private InstrumentRepository repository;
	
	@Mock
	private InstrumentMapper mapper;
	
	@InjectMocks
	private InstrumentServiceImpl service;
	
	@Test
	void save_shouldCreate_whenNameUnique() {
		var dto = new InstrumentSaveRequestDto("Drums");
		var entity = Instrument.builder().name("Drums").build();
		var saved = Instrument.builder().name("Drums").build();
		saved.setId(UUID.randomUUID());
		var resp = new InstrumentResponseDto(
				// id tipine göre doldur:
				saved.getId().toString(),
				"Drums"
		);
		
		when(repository.existsByName("Drums")).thenReturn(false);
		when(mapper.toInstrument(dto)).thenReturn(entity);
		when(repository.save(entity)).thenReturn(saved);
		when(mapper.toInstrumentResponseDto(saved)).thenReturn(resp);
		
		InstrumentResponseDto result = service.save(dto);
		
		assertThat(result.name()).isEqualTo("Drums");
		verify(repository).save(entity);
	}
	
	@Test
	void save_shouldThrow_whenDuplicateName() {
		var dto = new InstrumentSaveRequestDto("Drums");
		when(repository.existsByName("Drums")).thenReturn(true);
		
		assertThatThrownBy(() -> service.save(dto))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.INSTRUMENT_ALREADY_EXISTS));
		
		verify(repository, never()).save(any());
	}
	
	@Test
	void findAll_shouldReturnMappedList() {
		var e1 = Instrument.builder().name("Guitar").build();
		var e2 = Instrument.builder().name("Bass").build();
		e1.setId(UUID.randomUUID());
		e2.setId(UUID.randomUUID());
		
		when(repository.findAll()).thenReturn(List.of(e1, e2));
		when(mapper.toInstrumentResponseDto(e1))
				.thenReturn(new InstrumentResponseDto(e1.getId().toString(), "Guitar"));
		when(mapper.toInstrumentResponseDto(e2))
				.thenReturn(new InstrumentResponseDto(e2.getId().toString(), "Bass"));
		
		var list = service.findAll();
		
		assertThat(list).hasSize(2);
		assertThat(list).extracting(InstrumentResponseDto::name).containsExactlyInAnyOrder("Guitar", "Bass");
	}
	
	@Test
	void findById_shouldReturn_whenExists() {
		var id = UUID.randomUUID();
		var e = Instrument.builder().name("Piano").build();
		e.setId(id);
		
		when(repository.findById(id)).thenReturn(Optional.of(e));
		when(mapper.toInstrumentResponseDto(e))
				.thenReturn(new InstrumentResponseDto(id.toString(), "Piano"));
		
		var result = service.findById(id);
		
		assertThat(result.name()).isEqualTo("Piano");
	}
	
	@Test
	void findById_shouldThrow_whenNotFound() {
		var id = UUID.randomUUID();
		when(repository.findById(id)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.findById(id))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.INSTRUMENT_NOT_FOUND));
	}
	
	@Test
	void deleteById_shouldDelete_whenExists() {
		var id = UUID.randomUUID();
		when(repository.existsById(id)).thenReturn(true);
		
		service.deleteById(id);
		
		verify(repository).deleteById(id);
	}
	
	@Test
	void deleteById_shouldThrow_whenNotFound() {
		var id = UUID.randomUUID();
		when(repository.existsById(id)).thenReturn(false);
		
		assertThatThrownBy(() -> service.deleteById(id))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.INSTRUMENT_NOT_FOUND));
		
		verify(repository, never()).deleteById(any());
	}
}