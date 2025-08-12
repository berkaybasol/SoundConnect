package com.berkayb.soundconnect.modules.location.service;

import com.berkayb.soundconnect.modules.location.dto.request.NeighborhoodRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.NeighborhoodResponseDto;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.mapper.NeighborhoodMapper;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NeighborhoodServiceImplTest {
	
	@Mock private NeighborhoodRepository neighborhoodRepository;
	@Mock private DistrictRepository districtRepository; // ctor bağımlılığı
	@Mock private NeighborhoodMapper neighborhoodMapper;
	@Mock private LocationEntityFinder locationEntityFinder;
	
	@InjectMocks
	private NeighborhoodServiceImpl neighborhoodService;
	
	AutoCloseable closeable;
	
	@BeforeEach
	void init() { closeable = MockitoAnnotations.openMocks(this); }
	
	@Test
	void save_WhenDuplicate_ShouldThrow() {
		UUID districtId = UUID.randomUUID();
		NeighborhoodRequestDto dto = new NeighborhoodRequestDto("Moda", districtId);
		
		when(neighborhoodRepository.existsByNameAndDistrict_Id("Moda", districtId)).thenReturn(true);
		
		assertThatThrownBy(() -> neighborhoodService.save(dto))
				.isInstanceOf(SoundConnectException.class);
		
		verify(neighborhoodRepository).existsByNameAndDistrict_Id("Moda", districtId);
		verifyNoMoreInteractions(neighborhoodRepository, neighborhoodMapper);
	}
	
	@Test
	void save_WhenOk_ShouldAttachDistrictPersistAndMap() {
		UUID districtId = UUID.randomUUID();
		NeighborhoodRequestDto dto = new NeighborhoodRequestDto("Moda", districtId);
		
		District district = District.builder().id(districtId).name("Kadıköy").build();
		Neighborhood entity = Neighborhood.builder().name("Moda").build();
		Neighborhood saved = Neighborhood.builder().id(UUID.randomUUID()).name("Moda").district(district).build();
		NeighborhoodResponseDto resp = new NeighborhoodResponseDto(saved.getId(), "Moda", districtId);
		
		when(neighborhoodRepository.existsByNameAndDistrict_Id("Moda", districtId)).thenReturn(false);
		when(locationEntityFinder.getDistrict(districtId)).thenReturn(district);
		when(neighborhoodMapper.toEntity(dto)).thenReturn(entity);
		when(neighborhoodRepository.save(entity)).thenReturn(saved);
		when(neighborhoodMapper.toResponse(saved)).thenReturn(resp);
		
		var out = neighborhoodService.save(dto);
		
		assertThat(out).isEqualTo(resp);
		assertThat(entity.getDistrict()).isEqualTo(district);
		
		verify(neighborhoodRepository).existsByNameAndDistrict_Id("Moda", districtId);
		verify(locationEntityFinder).getDistrict(districtId);
		verify(neighborhoodMapper).toEntity(dto);
		verify(neighborhoodRepository).save(entity);
		verify(neighborhoodMapper).toResponse(saved);
	}
	
	@Test
	void findAll_ShouldMapList() {
		Neighborhood n = Neighborhood.builder().id(UUID.randomUUID()).name("X").build();
		when(neighborhoodRepository.findAll()).thenReturn(List.of(n));
		NeighborhoodResponseDto r = new NeighborhoodResponseDto(n.getId(), "X", null);
		when(neighborhoodMapper.toResponseList(List.of(n))).thenReturn(List.of(r));
		
		var out = neighborhoodService.findAll();
		assertThat(out).containsExactly(r);
		
		verify(neighborhoodRepository).findAll();
		verify(neighborhoodMapper).toResponseList(List.of(n));
	}
	
	@Test
	void findById_WhenNotFound_ShouldThrow() {
		UUID id = UUID.randomUUID();
		when(neighborhoodRepository.findById(id)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> neighborhoodService.findById(id))
				.isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	void findById_WhenFound_ShouldMap() {
		UUID id = UUID.randomUUID();
		Neighborhood n = Neighborhood.builder().id(id).name("Y").build();
		when(neighborhoodRepository.findById(id)).thenReturn(Optional.of(n));
		NeighborhoodResponseDto r = new NeighborhoodResponseDto(id, "Y", null);
		when(neighborhoodMapper.toResponse(n)).thenReturn(r);
		
		var out = neighborhoodService.findById(id);
		assertThat(out).isEqualTo(r);
		
		verify(neighborhoodRepository).findById(id);
		verify(neighborhoodMapper).toResponse(n);
	}
	
	@Test
	void findByDistrictId_ShouldMapList() {
		UUID districtId = UUID.randomUUID();
		Neighborhood n = Neighborhood.builder().id(UUID.randomUUID()).name("Z").build();
		when(neighborhoodRepository.findAllByDistrict_Id(districtId)).thenReturn(List.of(n));
		NeighborhoodResponseDto r = new NeighborhoodResponseDto(n.getId(), "Z", districtId);
		when(neighborhoodMapper.toResponseList(List.of(n))).thenReturn(List.of(r));
		
		var out = neighborhoodService.findByDistrictId(districtId);
		assertThat(out).containsExactly(r);
		
		verify(neighborhoodRepository).findAllByDistrict_Id(districtId);
		verify(neighborhoodMapper).toResponseList(List.of(n));
	}
	
	@Test
	void delete_ShouldUseFinderThenRepoDelete() {
		UUID id = UUID.randomUUID();
		Neighborhood n = Neighborhood.builder().id(id).name("Del").build();
		when(locationEntityFinder.getNeighborhood(id)).thenReturn(n);
		
		neighborhoodService.delete(id);
		
		verify(locationEntityFinder).getNeighborhood(id);
		verify(neighborhoodRepository).delete(n);
	}
}