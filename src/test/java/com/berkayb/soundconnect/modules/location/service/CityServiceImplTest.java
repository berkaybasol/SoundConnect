package com.berkayb.soundconnect.modules.location.service;

import com.berkayb.soundconnect.modules.location.dto.request.CityRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.CityPrettyDto;
import com.berkayb.soundconnect.modules.location.dto.response.CityResponseDto;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.mapper.CityMapper;
import com.berkayb.soundconnect.modules.location.mapper.CityPrettyMapper;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("service")
class CityServiceImplTest {
	
	@Mock private CityRepository cityRepository;
	@Mock private CityMapper cityMapper;
	@Mock private CityPrettyMapper cityPrettyMapper; // ctor bağımlılığı, bu testte kullanılmıyor
	@Mock private LocationEntityFinder locationEntityFinder;
	
	@InjectMocks
	private CityServiceImpl cityService;
	
	AutoCloseable closeable;
	
	@BeforeEach
	void init() {
		closeable = MockitoAnnotations.openMocks(this);
	}
	
	@Test
	void save_WhenNameExists_ShouldThrow() {
		CityRequestDto dto = new CityRequestDto("Ankara");
		when(cityRepository.existsByName("Ankara")).thenReturn(true);
		
		assertThatThrownBy(() -> cityService.save(dto))
				.isInstanceOf(SoundConnectException.class);
		
		verify(cityRepository).existsByName("Ankara");
		verifyNoMoreInteractions(cityRepository, cityMapper);
	}
	
	@Test
	void save_WhenOk_ShouldMapPersistAndReturnResponse() {
		CityRequestDto dto = new CityRequestDto("İzmir");
		City entity = City.builder().name("İzmir").build();
		City saved = City.builder().id(UUID.randomUUID()).name("İzmir").build();
		CityResponseDto resp = new CityResponseDto(saved.getId(), "İzmir");
		
		when(cityRepository.existsByName("İzmir")).thenReturn(false);
		when(cityMapper.toEntity(dto)).thenReturn(entity);
		when(cityRepository.save(entity)).thenReturn(saved);
		when(cityMapper.toResponse(saved)).thenReturn(resp);
		
		CityResponseDto out = cityService.save(dto);
		
		assertThat(out).isEqualTo(resp);
		verify(cityRepository).existsByName("İzmir");
		verify(cityMapper).toEntity(dto);
		verify(cityRepository).save(entity);
		verify(cityMapper).toResponse(saved);
	}
	
	@Test
	void findAll_ShouldMapList() {
		City c1 = City.builder().id(UUID.randomUUID()).name("Üsküdar").build();
		City c2 = City.builder().id(UUID.randomUUID()).name("İstanbul").build();
		City c3 = City.builder().id(UUID.randomUUID()).name("Iğdır").build();
		when(cityRepository.findAll()).thenReturn(List.of(c1, c2, c3));
		
		CityResponseDto d1 = new CityResponseDto(c1.getId(), c1.getName());
		CityResponseDto d2 = new CityResponseDto(c2.getId(), c2.getName());
		CityResponseDto d3 = new CityResponseDto(c3.getId(), c3.getName());
		when(cityMapper.toResponseList(List.of(c3, c2, c1))).thenReturn(List.of(d3, d2, d1));
		
		List<CityResponseDto> out = cityService.findAll();
		assertThat(out).containsExactly(d3, d2, d1);
		
		verify(cityRepository).findAll();
		verify(cityMapper).toResponseList(List.of(c3, c2, c1));
	}

	@Test
	void findAllPretty_ShouldSortCitiesBeforeMapping() {
		City uskudar = City.builder().id(UUID.randomUUID()).name("Üsküdar").build();
		City istanbul = City.builder().id(UUID.randomUUID()).name("İstanbul").build();
		City igdir = City.builder().id(UUID.randomUUID()).name("Iğdır").build();
		when(cityRepository.findAll()).thenReturn(List.of(uskudar, istanbul, igdir));
		when(cityPrettyMapper.toPretty(igdir)).thenReturn(new CityPrettyDto("Iğdır", List.of()));
		when(cityPrettyMapper.toPretty(istanbul)).thenReturn(new CityPrettyDto("İstanbul", List.of()));
		when(cityPrettyMapper.toPretty(uskudar)).thenReturn(new CityPrettyDto("Üsküdar", List.of()));

		List<CityPrettyDto> result = cityService.findAllPretty();

		assertThat(result).extracting(CityPrettyDto::name)
				.containsExactly("Iğdır", "İstanbul", "Üsküdar");
	}
	
	@Test
	void findById_ShouldUseFinderAndMap() {
		UUID id = UUID.randomUUID();
		City c = City.builder().id(id).name("A").build();
		when(locationEntityFinder.getCity(id)).thenReturn(c);
		CityResponseDto d = new CityResponseDto(id, "A");
		when(cityMapper.toResponse(c)).thenReturn(d);
		
		CityResponseDto out = cityService.findById(id);
		assertThat(out).isEqualTo(d);
		
		verify(locationEntityFinder).getCity(id);
		verify(cityMapper).toResponse(c);
	}
	
	@Test
	void delete_ShouldUseFinderThenRepoDelete() {
		UUID id = UUID.randomUUID();
		City c = City.builder().id(id).name("A").build();
		when(locationEntityFinder.getCity(id)).thenReturn(c);
		
		cityService.delete(id);
		
		verify(locationEntityFinder).getCity(id);
		verify(cityRepository).delete(c);
	}
}
