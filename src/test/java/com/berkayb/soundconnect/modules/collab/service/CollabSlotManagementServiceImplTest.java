package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabFillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUnfillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabRequiredSlot;
import com.berkayb.soundconnect.modules.collab.mapper.CollabMapper;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.support.finder.CollabEntityFinder;
import com.berkayb.soundconnect.modules.collab.support.validations.CollabValidations;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.support.InstrumentEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollabSlotManagementServiceImplTest {
	
	@InjectMocks
	CollabSlotManagementServiceImpl service;
	
	@Mock CollabRepository collabRepository;
	@Mock CollabMapper collabMapper;
	@Mock CollabEntityFinder collabEntityFinder;
	@Mock UserEntityFinder userEntityFinder;
	@Mock InstrumentEntityFinder instrumentEntityFinder;
	@Mock CollabValidations collabValidations;
	
	@Test
	@DisplayName("fill(): gerekli slot bulunur ve filledCount artar")
	void fill_ok() {
		
		UUID userId = UUID.randomUUID();
		UUID collabId = UUID.randomUUID();
		UUID instId = UUID.randomUUID();
		
		// ---- User ----
		User user = User.builder().id(userId).build();
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		
		// ---- Instrument ----
		Instrument inst = Instrument.builder().name("Gitar").build();
		inst.setId(instId);
		when(instrumentEntityFinder.getInstrument(instId)).thenReturn(inst);
		
		// ---- Required Slot ----
		CollabRequiredSlot slot = CollabRequiredSlot.builder()
		                                            .instrument(inst)
		                                            .requiredCount(2)
		                                            .filledCount(1)
		                                            .build();
		
		// ---- Collab ----
		Collab collab = Collab.builder()
		                      .id(collabId)
		                      .owner(user)                // ❗ Zorunlu
		                      .requiredSlots(new HashSet<>(Set.of(slot)))
		                      .build();
		
		slot.setCollab(collab); // ❗ Zorunlu
		
		when(collabEntityFinder.getCollab(collabId)).thenReturn(collab);
		
		// Validation stub → default success
		doNothing().when(collabValidations).validateOwner(collab, user);
		doNothing().when(collabValidations).validateNotExpired(collab);
		when(collabValidations.getRequiredSlot(collab, inst)).thenReturn(slot);
		
		CollabResponseDto resp = mock(CollabResponseDto.class);
		when(collabMapper.toResponseDto(collab, userId)).thenReturn(resp);
		
		CollabFillSlotRequestDto req = new CollabFillSlotRequestDto(instId);
		
		CollabResponseDto out = service.fill(userId, collabId, req);
		
		assertThat(out).isEqualTo(resp);
		assertThat(slot.getFilledCount()).isEqualTo(2);
		
		verify(collabRepository).save(collab);
	}
	
	@Test
	@DisplayName("unfill(): slot filledCount azaltır")
	void unfill_ok() {
		
		UUID userId = UUID.randomUUID();
		UUID collabId = UUID.randomUUID();
		UUID instId = UUID.randomUUID();
		
		User user = User.builder().id(userId).build();
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		
		Instrument inst = Instrument.builder().name("Bas").build();
		inst.setId(instId);
		when(instrumentEntityFinder.getInstrument(instId)).thenReturn(inst);
		
		CollabRequiredSlot slot = CollabRequiredSlot.builder()
		                                            .instrument(inst)
		                                            .requiredCount(3)
		                                            .filledCount(2)
		                                            .build();
		
		Collab collab = Collab.builder()
		                      .id(collabId)
		                      .owner(user)           // ❗ Zorunlu
		                      .requiredSlots(new HashSet<>(Set.of(slot)))
		                      .build();
		
		slot.setCollab(collab); // ❗ Zorunlu
		
		when(collabEntityFinder.getCollab(collabId)).thenReturn(collab);
		
		doNothing().when(collabValidations).validateOwner(collab, user);
		when(collabValidations.getRequiredSlot(collab, inst)).thenReturn(slot);
		
		CollabResponseDto resp = mock(CollabResponseDto.class);
		when(collabMapper.toResponseDto(collab, userId)).thenReturn(resp);
		
		CollabUnfillSlotRequestDto req = new CollabUnfillSlotRequestDto(instId);
		
		CollabResponseDto out = service.unfill(userId, collabId, req);
		
		assertThat(out).isEqualTo(resp);
		assertThat(slot.getFilledCount()).isEqualTo(1);
		
		verify(collabRepository).save(collab);
	}
}