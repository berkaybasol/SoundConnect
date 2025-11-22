package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.*;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabRequiredSlot;
import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.collab.mapper.CollabMapper;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.ttl.service.CollabTTLService;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.support.InstrumentEntityFinder;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class CollabServiceImplTest {
	
	@InjectMocks
	CollabServiceImpl service;
	
	@Mock CollabRepository collabRepository;
	@Mock CollabMapper collabMapper;
	@Mock UserEntityFinder userFinder;
	@Mock LocationEntityFinder locationEntityFinder;
	@Mock InstrumentEntityFinder instrumentEntityFinder;
	@Mock CollabTTLService collabTTLService;
	@Mock VenueRepository venueRepository;
	@Mock BandMemberRepository bandMemberRepository;
	
	// -------------------------------------------------------------------------
	@Test
	@DisplayName("create çalışır")
	void create_ok() {
		
		UUID ownerId = UUID.randomUUID();
		User owner = User.builder().id(ownerId).build();
		owner.setMusicianProfile(new MusicianProfile());
		
		when(userFinder.getUser(ownerId)).thenReturn(owner);
		
		UUID cityId = UUID.randomUUID();
		City city = City.builder().id(cityId).name("Ankara").build();
		when(locationEntityFinder.getCity(cityId)).thenReturn(city);
		
		UUID inst1 = UUID.randomUUID();
		Instrument i1 = Instrument.builder().name("Gitar").build();
		i1.setId(inst1);
		when(instrumentEntityFinder.getInstrument(inst1)).thenReturn(i1);
		
		RequiredSlotRequestDto slotReq = new RequiredSlotRequestDto(inst1, 2);
		
		CollabCreateRequestDto req = new CollabCreateRequestDto(
				"Başlık", "desc",
				CollabCategory.GIG,
				Set.of(CollabRole.MUSICIAN),
				cityId,
				100,
				true,
				LocalDateTime.now().plusHours(2),
				Set.of(slotReq)
		);
		
		Collab collab = Collab.builder()
		                      .owner(owner)
		                      .daily(true)
		                      .requiredSlots(new HashSet<>())
		                      .build();
		
		when(collabMapper.toEntity(req)).thenReturn(collab);
		
		when(collabRepository.save(any())).thenAnswer(inv -> {
			Collab c = inv.getArgument(0);
			c.setId(UUID.randomUUID());
			return c;
		});
		
		CollabResponseDto resp = mock(CollabResponseDto.class);
		when(collabMapper.toResponseDto(any(), eq(ownerId))).thenReturn(resp);
		
		CollabResponseDto out = service.create(ownerId, req);
		
		assertThat(out).isEqualTo(resp);
		verify(collabTTLService).setTTL(eq(collab.getId()), any());
	}
	
	
	// -------------------------------------------------------------------------
	@Test
	@DisplayName("update çalışır")
	void update_ok() {
		
		UUID ownerId = UUID.randomUUID();
		UUID collabId = UUID.randomUUID();
		
		User owner = User.builder().id(ownerId).build();
		
		LocalDateTime oldExp = LocalDateTime.of(2030, 1, 1, 10, 0);
		LocalDateTime newExp = LocalDateTime.of(2030, 1, 1, 12, 0);
		
		City city = City.builder().id(UUID.randomUUID()).name("İstanbul").build();
		when(locationEntityFinder.getCity(city.getId())).thenReturn(city);
		
		Collab existing = Collab.builder()
		                        .id(collabId)
		                        .owner(owner)
		                        .daily(true)
		                        .expirationTime(oldExp)
		                        .requiredSlots(new HashSet<>())
		                        .build();
		
		when(collabRepository.findByIdAndOwner_Id(collabId, ownerId))
				.thenReturn(Optional.of(existing));
		
		// --- Mapper updateEntity davranışını stable hale getiriyoruz ---
		doAnswer(inv -> {
			Collab target = inv.getArgument(0);
			CollabUpdateRequestDto dto = inv.getArgument(1);
			target.setTitle(dto.title());
			target.setDescription(dto.description());
			target.setCategory(dto.category());
			target.setTargetRoles(dto.targetRoles());
			target.setPrice(dto.price());
			target.setDaily(dto.daily());
			target.setExpirationTime(dto.expirationTime()); // KRİTİK
			return null;
		}).when(collabMapper).updateEntity(any(Collab.class), any(CollabUpdateRequestDto.class));
		
		// --- Instrument ---
		UUID inst1 = UUID.randomUUID();
		Instrument i1 = Instrument.builder().name("Gitar").build();
		i1.setId(inst1);
		
		when(instrumentEntityFinder.getInstrument(inst1)).thenReturn(i1);
		
		CollabUpdateRequestDto req = new CollabUpdateRequestDto(
				"NewTitle",
				"newdesc",
				CollabCategory.RECORDING,
				Set.of(CollabRole.PRODUCER),
				city.getId(),
				200,
				true,
				newExp,
				Set.of(new RequiredSlotRequestDto(inst1, 3))
		);
		
		CollabResponseDto resp = mock(CollabResponseDto.class);
		when(collabMapper.toResponseDto(existing, ownerId)).thenReturn(resp);
		
		CollabResponseDto out = service.update(collabId, ownerId, req);
		
		assertThat(out).isEqualTo(resp);
		
		verify(collabTTLService).resetTTL(collabId, newExp);
	}
	
	// -------------------------------------------------------------------------
	@Test
	@DisplayName("delete çalışır")
	void delete_ok() {
		UUID ownerId = UUID.randomUUID();
		UUID collabId = UUID.randomUUID();
		
		User owner = User.builder().id(ownerId).build();
		Collab collab = Collab.builder().id(collabId).owner(owner).build();
		
		when(collabRepository.findByIdAndOwner_Id(collabId, ownerId)).thenReturn(Optional.of(collab));
		
		service.delete(collabId, ownerId);
		
		verify(collabTTLService).deleteTTL(collabId);
		verify(collabRepository).delete(collab);
	}
	
	// -------------------------------------------------------------------------
	@Test
	@DisplayName("getById çalışır")
	void getById_ok() {
		
		UUID collabId = UUID.randomUUID();
		
		Collab collab = Collab.builder().id(collabId).build();
		when(collabRepository.findById(collabId)).thenReturn(Optional.of(collab));
		
		CollabResponseDto dto = mock(CollabResponseDto.class);
		when(collabMapper.toResponseDto(collab, null)).thenReturn(dto);
		
		CollabResponseDto out = service.getById(collabId, null);
		
		assertThat(out).isEqualTo(dto);
	}
	
	// -------------------------------------------------------------------------
	@Test
	@DisplayName("search çalışır")
	void search_ok() {
		
		CollabFilterRequestDto filter = new CollabFilterRequestDto(
				UUID.randomUUID(),
				CollabCategory.GIG,
				null, null, null, null, null, null, null
		);
		
		Collab c = Collab.builder().id(UUID.randomUUID()).build();
		
		Page<Collab> page = new PageImpl<>(List.of(c));
		
		when(collabRepository.findAll(
				Mockito.<org.springframework.data.jpa.domain.Specification<Collab>>any(),
				any(Pageable.class)
		)).thenReturn(page);
		
		CollabResponseDto dto = mock(CollabResponseDto.class);
		when(collabMapper.toResponseDto(any(), isNull())).thenReturn(dto);
		
		Page<CollabResponseDto> out = service.search(null, filter, PageRequest.of(0, 10));
		
		assertThat(out.getContent().get(0)).isEqualTo(dto);
	}
}