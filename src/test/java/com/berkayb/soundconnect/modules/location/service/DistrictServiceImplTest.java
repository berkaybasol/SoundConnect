package com.berkayb.soundconnect.modules.location.service;

import com.berkayb.soundconnect.modules.location.dto.request.DistrictRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.DistrictResponseDto;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.mapper.DistrictMapper;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DistrictServiceImplTest {
	
	@Mock private DistrictRepository districtRepository;
	@Mock private CityRepository cityRepository; // ctor bağımlılığı, save akışında kullanılmıyor ama mevcut
	@Mock private DistrictMapper districtMapper;
	@Mock private LocationEntityFinder locationEntityFinder;
	
	@InjectMocks
	private DistrictServiceImpl districtService;
	
	AutoCloseable closeable;
	
	@BeforeEach
	void init() { closeable = MockitoAnnotations.openMocks(this); }
	
	@Test
	void save_WhenDuplicate_ShouldThrow() {
		UUID cityId = UUID.randomUUID();
		DistrictRequestDto dto = new DistrictRequestDto("Çankaya", cityId);
		when(districtRepository.existsByNameAndCity_Id("Çankaya", cityId)).thenReturn(true);
		
		assertThatThrownBy(() -> districtService.save(dto))
				.isInstanceOf(SoundConnectException.class);
		
		verify(districtRepository).existsByNameAndCity_Id("Çankaya", cityId);
		verifyNoMoreInteractions(districtRepository, districtMapper);
	}
	
	@Test
	void save_WhenOk_ShouldAttachCityPersistAndMap() {
		UUID cityId = UUID.randomUUID();
		DistrictRequestDto dto = new DistrictRequestDto("Çankaya", cityId);
		
		City city = City.builder().id(cityId).name("Ankara").build();
		District entity = District.builder().name("Çankaya").build();
		District saved = District.builder().id(UUID.randomUUID()).name("Çankaya").city(city).build();
		DistrictResponseDto resp = new DistrictResponseDto(saved.getId(), "Çankaya", cityId);
		
		when(districtRepository.existsByNameAndCity_Id("Çankaya", cityId)).thenReturn(false);
		when(locationEntityFinder.getCity(cityId)).thenReturn(city);
		when(districtMapper.toEntity(dto)).thenReturn(entity);
		// service setCity(city) yapıyor:
		when(districtRepository.save(entity)).thenReturn(saved);
		when(districtMapper.toResponse(saved)).thenReturn(resp);
		
		DistrictResponseDto out = districtService.save(dto);
		
		assertThat(out).isEqualTo(resp);
		assertThat(entity.getCity()).isEqualTo(city);
		
		verify(districtRepository).existsByNameAndCity_Id("Çankaya", cityId);
		verify(locationEntityFinder).getCity(cityId);
		verify(districtMapper).toEntity(dto);
		verify(districtRepository).save(entity);
		verify(districtMapper).toResponse(saved);
	}
	
	@Test
	void findAll_ShouldMapList() {
		District d1 = District.builder().id(UUID.randomUUID()).name("A").build();
		District d2 = District.builder().id(UUID.randomUUID()).name("B").build();
		
		when(districtRepository.findAll()).thenReturn(List.of(d1, d2));
		DistrictResponseDto r1 = new DistrictResponseDto(d1.getId(), "A", null);
		DistrictResponseDto r2 = new DistrictResponseDto(d2.getId(), "B", null);
		when(districtMapper.toResponseList(List.of(d1, d2))).thenReturn(List.of(r1, r2));
		
		var out = districtService.findAll();
		assertThat(out).containsExactly(r1, r2);
		
		verify(districtRepository).findAll();
		verify(districtMapper).toResponseList(List.of(d1, d2));
	}
	
	@Test
	void findByCityId_ShouldMapList() {
		UUID cityId = UUID.randomUUID();
		District d = District.builder().id(UUID.randomUUID()).name("X").build();
		when(districtRepository.findDistrictsByCity_Id(cityId)).thenReturn(List.of(d));
		DistrictResponseDto r = new DistrictResponseDto(d.getId(), "X", cityId);
		when(districtMapper.toResponseList(List.of(d))).thenReturn(List.of(r));
		
		var out = districtService.findByCityId(cityId);
		assertThat(out).containsExactly(r);
		
		verify(districtRepository).findDistrictsByCity_Id(cityId);
		verify(districtMapper).toResponseList(List.of(d));
	}
	
	@Test
	void findById_ShouldUseFinderAndMap() {
		UUID id = UUID.randomUUID();
		District d = District.builder().id(id).name("Y").build();
		when(locationEntityFinder.getDistrict(id)).thenReturn(d);
		DistrictResponseDto r = new DistrictResponseDto(id, "Y", null);
		when(districtMapper.toResponse(d)).thenReturn(r);
		
		var out = districtService.findById(id);
		assertThat(out).isEqualTo(r);
		
		verify(locationEntityFinder).getDistrict(id);
		verify(districtMapper).toResponse(d);
	}
	
	@Test
	void delete_ShouldUseFinderThenRepoDelete() {
		UUID id = UUID.randomUUID();
		District d = District.builder().id(id).name("Z").build();
		when(locationEntityFinder.getDistrict(id)).thenReturn(d);
		
		districtService.delete(id);
		
		verify(locationEntityFinder).getDistrict(id);
		verify(districtRepository).delete(d);
	}
}