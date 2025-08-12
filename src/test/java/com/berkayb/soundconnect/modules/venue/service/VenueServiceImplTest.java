package com.berkayb.soundconnect.modules.venue.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.dto.request.VenueRequestDto;
import com.berkayb.soundconnect.modules.venue.dto.response.VenueResponseDto;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.mapper.VenueMapper;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VenueServiceImplTest {
	
	@Mock private VenueRepository venueRepository;
	@Mock private VenueMapper venueMapper;
	@Mock private LocationEntityFinder locationEntityFinder;
	@Mock private UserEntityFinder userEntityFinder;
	@Mock private VenueEntityFinder venueEntityFinder;
	@Mock private RoleRepository roleRepository;
	@Mock private UserRepository userRepository;
	@Mock private VenueProfileService venueProfileService;
	
	@InjectMocks
	private VenueServiceImpl sut; // system under test
	
	// common fixtures
	private UUID cityId;
	private UUID districtId;
	private UUID neighborhoodId;
	private UUID ownerId;
	
	private City city;
	private District district;
	private Neighborhood neighborhood;
	private User owner;
	private Role venueRole;
	
	private VenueRequestDto dto;
	
	@BeforeEach
	void init() {
		cityId = UUID.randomUUID();
		districtId = UUID.randomUUID();
		neighborhoodId = UUID.randomUUID();
		ownerId = UUID.randomUUID();
		
		city = City.builder().id(cityId).name("Ankara").build();
		district = District.builder().id(districtId).name("Çankaya").city(city).build();
		neighborhood = Neighborhood.builder().id(neighborhoodId).name("Bahçelievler").district(district).build();
		
		owner = User.builder()
		            .id(ownerId)
		            .username("basol")
		            .password("encoded")
		            .roles(new HashSet<>())   // <-- Set.of() DEĞİL, mutable olsun
		            .status(UserStatus.PENDING_VENUE_REQUEST) // save sırasında ACTIVE olacak
		            .build();
		
		venueRole = Role.builder().id(UUID.randomUUID()).name(RoleEnum.ROLE_VENUE.name()).build();
		
		dto = new VenueRequestDto(
				"KaraKedi",
				"Adres 123",
				cityId, districtId, neighborhoodId,
				ownerId,
				"05321234567",
				"https://karakedi.example",
				"Canlı müzik",
				"21:00"
		);
	}
	
	@Test
	void save_ok() {
		// --- arrange (given) ---
		// finder stubs
		when(locationEntityFinder.getCity(dto.cityId())).thenReturn(city);
		when(locationEntityFinder.getDistrict(dto.districtId())).thenReturn(district);
		when(locationEntityFinder.getNeighborhood(dto.neighborhoodId())).thenReturn(neighborhood);
		when(userEntityFinder.getUser(dto.ownerId())).thenReturn(owner);
		
		// mapper.toEntity -> gerçek entity
		Venue mapped = Venue.builder()
		                    .name(dto.name())
		                    .address(dto.address())
		                    .phone(dto.phone())
		                    .website(dto.website())
		                    .description(dto.description())
		                    .musicStartTime(dto.musicStartTime())
		                    .city(city)
		                    .district(district)
		                    .neighborhood(neighborhood)
		                    .owner(owner)
		                    .build();
		when(venueMapper.toEntity(eq(dto), eq(city), eq(district), eq(neighborhood), eq(owner)))
				.thenReturn(mapped);
		
		// repo.save -> id verip geri döndür
		when(venueRepository.save(any(Venue.class)))
				.thenAnswer(inv -> {
					Venue v = inv.getArgument(0);
					v.setId(UUID.randomUUID());
					return v;
				});
		
		// role repo
		when(roleRepository.findByName(RoleEnum.ROLE_VENUE.name()))
				.thenReturn(Optional.of(venueRole));
		
		// user save
		when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
		
		// mapper.toResponse -> response üret
		when(venueMapper.toResponse(any(Venue.class))).thenAnswer(inv -> {
			Venue v = inv.getArgument(0);
			return new VenueResponseDto(
					v.getId(),
					v.getName(), v.getAddress(), v.getPhone(), v.getWebsite(), v.getDescription(), v.getMusicStartTime(),
					v.getCity().getName(), v.getDistrict().getName(), v.getNeighborhood().getName(),
					VenueStatus.APPROVED,
					v.getOwner().getId(), v.getOwner().getUsername(),
					Set.of()
			);
		});
		
		// --- act (when) ---
		VenueResponseDto res = sut.save(dto);
		
		// --- assert (then) ---
		// 1) response alanları
		assertThat(res).isNotNull();
		assertThat(res.name()).isEqualTo(dto.name());
		assertThat(res.address()).isEqualTo(dto.address());
		assertThat(res.cityName()).isEqualTo("Ankara");
		assertThat(res.districtName()).isEqualTo("Çankaya");
		assertThat(res.neighborhoodName()).isEqualTo("Bahçelievler");
		assertThat(res.status()).isEqualTo(VenueStatus.APPROVED);
		assertThat(res.ownerId()).isEqualTo(ownerId);
		assertThat(res.ownerFullName()).isEqualTo("basol");
		
		// 2) entity tarafı yan etkiler
		// mapped entity save öncesi sut.save içinde APPROVED yapılmalı
		assertThat(mapped.getStatus()).isEqualTo(VenueStatus.APPROVED);
		
		// 3) owner’a ROLE_VENUE eklenmeli ve status ACTIVE olmalı
		assertThat(owner.getRoles().stream().anyMatch(r -> r.getName().equals(RoleEnum.ROLE_VENUE.name()))).isTrue();
		assertThat(owner.getStatus()).isEqualTo(UserStatus.ACTIVE);
		
		// 4) venue profile create çağrısı yapılmalı (id ile)
		verify(venueProfileService, times(1))
				.createProfile(eq(res.id()), any(VenueProfileSaveRequestDto.class));
		
		// 5) çağrı sırası (opsiyonel ama güzel bir güvence)
		InOrder inOrder = inOrder(locationEntityFinder, userEntityFinder, venueMapper, venueRepository, roleRepository, userRepository, venueProfileService, venueMapper);
		inOrder.verify(locationEntityFinder).getCity(dto.cityId());
		inOrder.verify(locationEntityFinder).getDistrict(dto.districtId());
		inOrder.verify(locationEntityFinder).getNeighborhood(dto.neighborhoodId());
		inOrder.verify(userEntityFinder).getUser(dto.ownerId());
		inOrder.verify(venueMapper).toEntity(eq(dto), eq(city), eq(district), eq(neighborhood), eq(owner));
		inOrder.verify(venueRepository).save(any(Venue.class));
		inOrder.verify(roleRepository).findByName(RoleEnum.ROLE_VENUE.name());
		inOrder.verify(userRepository).save(any(User.class));
		inOrder.verify(venueProfileService).createProfile(any(UUID.class), any(VenueProfileSaveRequestDto.class));
		inOrder.verify(venueMapper).toResponse(any(Venue.class));
		inOrder.verifyNoMoreInteractions();
	}
	@Test
	void update_ok() {
		// --- arrange ---
		UUID venueId = UUID.randomUUID();
		
		// mevcut (eski) venue
		Venue existing = Venue.builder()
		                      .id(venueId)
		                      .name("EskiAd")
		                      .address("EskiAdres")
		                      .phone("05000000000")
		                      .website("https://old.example")
		                      .description("Eski açıklama")
		                      .musicStartTime("20:00")
		                      .city(city)
		                      .district(district)
		                      .neighborhood(neighborhood)
		                      .owner(owner)
		                      .status(VenueStatus.PENDING)
		                      .build();
		
		when(venueEntityFinder.getVenue(venueId)).thenReturn(existing);
		
		// DTO ile gelen yeni değerler (init()'te hazırladığımız dto'yu kullanıyoruz)
		// dto: name=KaraKedi, address=Adres 123, phone=0532..., website=..., description=..., musicStartTime=21:00
		when(locationEntityFinder.getCity(dto.cityId())).thenReturn(city);
		when(locationEntityFinder.getDistrict(dto.districtId())).thenReturn(district);
		when(locationEntityFinder.getNeighborhood(dto.neighborhoodId())).thenReturn(neighborhood);
		when(userEntityFinder.getUser(dto.ownerId())).thenReturn(owner);
		
		// repo.save: aynı entity'yi geri döndürelim (updated state)
		when(venueRepository.save(any(Venue.class))).thenAnswer(inv -> inv.getArgument(0));
		
		// mapper.toResponse: updated entity'den response üretelim
		when(venueMapper.toResponse(any(Venue.class))).thenAnswer(inv -> {
			Venue v = inv.getArgument(0);
			return new VenueResponseDto(
					v.getId(),
					v.getName(), v.getAddress(), v.getPhone(), v.getWebsite(), v.getDescription(), v.getMusicStartTime(),
					v.getCity().getName(), v.getDistrict().getName(), v.getNeighborhood().getName(),
					v.getStatus(), // status değişmiyor; service sadece alanları güncelliyor
					v.getOwner().getId(), v.getOwner().getUsername(),
					Set.of()
			);
		});
		
		// --- act ---
		VenueResponseDto res = sut.update(venueId, dto);
		
		// --- assert ---
		assertThat(res).isNotNull();
		assertThat(res.id()).isEqualTo(venueId);
		assertThat(res.name()).isEqualTo(dto.name());
		assertThat(res.address()).isEqualTo(dto.address());
		assertThat(res.phone()).isEqualTo(dto.phone());
		assertThat(res.website()).isEqualTo(dto.website());
		assertThat(res.description()).isEqualTo(dto.description());
		assertThat(res.musicStartTime()).isEqualTo(dto.musicStartTime());
		assertThat(res.cityName()).isEqualTo("Ankara");
		assertThat(res.districtName()).isEqualTo("Çankaya");
		assertThat(res.neighborhoodName()).isEqualTo("Bahçelievler");
		// status serviste set edilmiyor; PENDING olarak kalır (existing'ten gelir)
		assertThat(res.status()).isEqualTo(VenueStatus.PENDING);
		assertThat(res.ownerId()).isEqualTo(owner.getId());
		assertThat(res.ownerFullName()).isEqualTo("basol");
		
		// entity gerçekten güncellendi mi?
		assertThat(existing.getName()).isEqualTo(dto.name());
		assertThat(existing.getAddress()).isEqualTo(dto.address());
		assertThat(existing.getPhone()).isEqualTo(dto.phone());
		assertThat(existing.getWebsite()).isEqualTo(dto.website());
		assertThat(existing.getDescription()).isEqualTo(dto.description());
		assertThat(existing.getMusicStartTime()).isEqualTo(dto.musicStartTime());
		assertThat(existing.getCity()).isSameAs(city);
		assertThat(existing.getDistrict()).isSameAs(district);
		assertThat(existing.getNeighborhood()).isSameAs(neighborhood);
		assertThat(existing.getOwner()).isSameAs(owner);
		
		// çağrı sırası (opsiyonel)
		InOrder inOrder = inOrder(venueEntityFinder, locationEntityFinder, userEntityFinder, venueRepository, venueMapper);
		inOrder.verify(venueEntityFinder).getVenue(venueId);
		inOrder.verify(locationEntityFinder).getCity(dto.cityId());
		inOrder.verify(locationEntityFinder).getDistrict(dto.districtId());
		inOrder.verify(locationEntityFinder).getNeighborhood(dto.neighborhoodId());
		inOrder.verify(userEntityFinder).getUser(dto.ownerId());
		inOrder.verify(venueRepository).save(any(Venue.class));
		inOrder.verify(venueMapper).toResponse(any(Venue.class));
		inOrder.verifyNoMoreInteractions();
	}
	@Test
	void findById_ok() {
		// arrange
		UUID venueId = UUID.randomUUID();
		Venue existing = Venue.builder()
		                      .id(venueId)
		                      .name("KaraKedi")
		                      .address("Adres 123")
		                      .phone("05321234567")
		                      .website("https://karakedi.example")
		                      .description("Canlı müzik")
		                      .musicStartTime("21:00")
		                      .city(city)
		                      .district(district)
		                      .neighborhood(neighborhood)
		                      .owner(owner)
		                      .status(VenueStatus.APPROVED)
		                      .build();
		
		when(venueEntityFinder.getVenue(venueId)).thenReturn(existing);
		when(venueMapper.toResponse(existing)).thenAnswer(inv -> {
			Venue v = inv.getArgument(0);
			return new VenueResponseDto(
					v.getId(),
					v.getName(), v.getAddress(), v.getPhone(), v.getWebsite(), v.getDescription(), v.getMusicStartTime(),
					v.getCity().getName(), v.getDistrict().getName(), v.getNeighborhood().getName(),
					v.getStatus(),
					v.getOwner().getId(), v.getOwner().getUsername(),
					Set.of()
			);
		});
		
		// act
		VenueResponseDto res = sut.findById(venueId);
		
		// assert
		assertThat(res.id()).isEqualTo(venueId);
		assertThat(res.name()).isEqualTo("KaraKedi");
		assertThat(res.cityName()).isEqualTo("Ankara");
		assertThat(res.status()).isEqualTo(VenueStatus.APPROVED);
		
		verify(venueEntityFinder).getVenue(venueId);
		verify(venueMapper).toResponse(existing);
		verifyNoMoreInteractions(venueEntityFinder, venueMapper);
	}
	@Test
	void delete_ok() {
		// arrange
		UUID venueId = UUID.randomUUID();
		when(venueRepository.existsById(venueId)).thenReturn(true);
		doNothing().when(venueRepository).deleteById(venueId);
		
		// act
		sut.delete(venueId);
		
		// assert
		InOrder inOrder = inOrder(venueRepository);
		inOrder.verify(venueRepository).existsById(venueId);
		inOrder.verify(venueRepository).deleteById(venueId);
		inOrder.verifyNoMoreInteractions();
	}
	@Test
	void delete_throws_when_not_found() {
		// arrange
		UUID venueId = UUID.randomUUID();
		when(venueRepository.existsById(venueId)).thenReturn(false);
		
		// act + assert
		assertThatThrownBy(() -> sut.delete(venueId))
				.isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class)
				.hasMessageContaining("Venue not found");
		
		verify(venueRepository).existsById(venueId);
		verifyNoMoreInteractions(venueRepository);
	}
	@Test
	void findAll_ok() {
		// arrange
		Venue v1 = Venue.builder().id(UUID.randomUUID()).name("KaraKedi").city(city).district(district).neighborhood(neighborhood).owner(owner).build();
		Venue v2 = Venue.builder().id(UUID.randomUUID()).name("SiyahBeyaz").city(city).district(district).neighborhood(neighborhood).owner(owner).build();
		when(venueRepository.findAll()).thenReturn(java.util.List.of(v1, v2));
		when(venueMapper.toResponseList(anyList())).thenAnswer(inv -> {
			@SuppressWarnings("unchecked")
			java.util.List<Venue> list = (java.util.List<Venue>) inv.getArgument(0);
			return list.stream().map(v -> new VenueResponseDto(
					v.getId(), v.getName(), null, null, null, null, null,
					v.getCity() != null ? v.getCity().getName() : null,
					v.getDistrict() != null ? v.getDistrict().getName() : null,
					v.getNeighborhood() != null ? v.getNeighborhood().getName() : null,
					null,
					v.getOwner() != null ? v.getOwner().getId() : null,
					v.getOwner() != null ? v.getOwner().getUsername() : null,
					Set.of()
			)).toList();
		});
		
		// act
		var res = sut.findAll();
		
		// assert
		assertThat(res).hasSize(2);
		assertThat(res.get(0).name()).isEqualTo("KaraKedi");
		assertThat(res.get(1).name()).isEqualTo("SiyahBeyaz");
		
		verify(venueRepository).findAll();
		verify(venueMapper).toResponseList(anyList());
		verifyNoMoreInteractions(venueRepository, venueMapper);
	}
	
}