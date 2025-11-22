package com.berkayb.soundconnect.modules.collab.mapper;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabCreateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUpdateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.RequiredSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.dto.response.SlotResponseDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabRequiredSlot;
import com.berkayb.soundconnect.modules.collab.enums.CollabCategory;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CollabMapperTest {
	
	private final CollabMapper mapper = Mappers.getMapper(CollabMapper.class);
	
	@Test
	@DisplayName("toEntity(createDto) → temel alanlar doğru set edilir, slot/city dokunulmaz")
	void toEntity_create_ok() {
		UUID cityId = UUID.randomUUID();
		
		CollabCreateRequestDto dto = new CollabCreateRequestDto(
				"Title",
				"Desc",
				CollabCategory.GIG,
				Set.of(CollabRole.MUSICIAN, CollabRole.PRODUCER),
				cityId,
				150,
				true,
				LocalDateTime.now().plusHours(3),
				Set.of(new RequiredSlotRequestDto(UUID.randomUUID(), 2))
		);
		
		Collab c = mapper.toEntity(dto);
		
		// Basit field mapping
		assertThat(c.getTitle()).isEqualTo("Title");
		assertThat(c.getDescription()).isEqualTo("Desc");
		assertThat(c.getCategory()).isEqualTo(CollabCategory.GIG);
		assertThat(c.getTargetRoles())
				.containsExactlyInAnyOrder(CollabRole.MUSICIAN, CollabRole.PRODUCER);
		assertThat(c.getPrice()).isEqualTo(150);
		assertThat(c.isDaily()).isTrue();
		assertThat(c.getExpirationTime()).isNotNull();
		
		// Service tarafında set edilecek alanlar mapper tarafından set edilmemeli
		assertThat(c.getOwner()).isNull();
		assertThat(c.getOwnerRole()).isNull();
		assertThat(c.getCity()).isNull();
		
		// Yeni slot sistemi: mapper create aşamasında slot üretmiyor
		assertThat(c.getRequiredSlots()).isNotNull();
		assertThat(c.getRequiredSlots()).isEmpty();
	}
	
	@Test
	@DisplayName("updateEntity → update edilebilir alanlar güncellenir, diğer alanlara dokunulmaz")
	void updateEntity_ok() {
		UUID collabId = UUID.randomUUID();
		
		// Mevcut collab
		City city = City.builder()
		                .name("Ankara")
		                .build();
		city.setId(UUID.randomUUID());
		
		CollabRequiredSlot existingSlot = CollabRequiredSlot.builder()
		                                                    .requiredCount(2)
		                                                    .filledCount(1)
		                                                    .build();
		
		Set<CollabRequiredSlot> existingSlots = new HashSet<>();
		existingSlots.add(existingSlot);
		
		Set<CollabRole> roles = new HashSet<>();
		roles.add(CollabRole.MUSICIAN);
		
		Collab collab = Collab.builder()
		                      .id(collabId)
		                      .title("OldTitle")
		                      .description("OldDesc")
		                      .category(CollabCategory.GIG)
		                      .targetRoles(roles)
		                      .price(50)
		                      .daily(false)
		                      .expirationTime(null)
		                      .city(city)
		                      .requiredSlots(existingSlots)
		                      .build();
		
		// Update DTO
		CollabUpdateRequestDto dto = new CollabUpdateRequestDto(
				"NewTitle",
				"NewDesc",
				CollabCategory.RECORDING,
				Set.of(CollabRole.PRODUCER),
				UUID.randomUUID(), // cityId - mapper bunu kullanmıyor
				200,
				true,
				LocalDateTime.now().plusHours(2),
				Set.of(new RequiredSlotRequestDto(UUID.randomUUID(), 3))
		);
		
		mapper.updateEntity(collab, dto);
		
		// Güncellenmesi gereken alanlar
		assertThat(collab.getTitle()).isEqualTo("NewTitle");
		assertThat(collab.getDescription()).isEqualTo("NewDesc");
		assertThat(collab.getCategory()).isEqualTo(CollabCategory.RECORDING);
		assertThat(collab.getTargetRoles())
				.containsExactly(CollabRole.PRODUCER);
		assertThat(collab.getPrice()).isEqualTo(200);
		assertThat(collab.isDaily()).isTrue();
		assertThat(collab.getExpirationTime()).isNotNull();
		
		// Dokunulmaması gereken alanlar
		assertThat(collab.getId()).isEqualTo(collabId);
		assertThat(collab.getCity()).isSameAs(city);
		assertThat(collab.getRequiredSlots())
				.hasSize(1)
				.containsExactly(existingSlot);
	}
	
	@Test
	@DisplayName("toResponseDto(collab) → context verilmezse isOwner false olur")
	void toResponseDto_withoutContext_isOwner_false() {
		UUID ownerId = UUID.randomUUID();
		
		User owner = User.builder()
		                 .username("veli")
		                 .password("pass")
		                 .email("mail2")
		                 .provider(AuthProvider.LOCAL)
		                 .emailVerified(true)
		                 .build();
		owner.setId(ownerId);
		
		Collab collab = Collab.builder()
		                      .id(UUID.randomUUID())
		                      .owner(owner)
		                      .ownerRole(CollabRole.MUSICIAN)
		                      .category(CollabCategory.GIG)
		                      .title("No Context Test")
		                      .description("Contextsiz mapper çağrısı")
		                      .daily(false)
		                      .build();
		
		CollabResponseDto dto = mapper.toResponseDto(collab); // @Context yok
		
		assertThat(dto.isOwner()).isFalse();
	}
}