package com.berkayb.soundconnect.modules.profile.MusicianProfile.service;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.mapper.MusicianProfileMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import jakarta.persistence.EntityManager;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@Tag("service")
class MusicianProfileServiceImplTest {
	
	@Mock MusicianProfileRepository repo;
	@Mock UserEntityFinder userFinder;
	@Mock InstrumentRepository instrumentRepo;
	@Mock MusicianProfileMapper mapper;
	@Mock BandService bandService;
	@Mock MediaAssetService mediaAssetService;
	@Mock PersonalProfileTypePolicy personalProfileTypePolicy;
	@Mock EntityManager entityManager;
	
	@InjectMocks
	MusicianProfileServiceImpl service;
	
	@Test
	void createProfile_shouldCreate_whenNotExists() {
		UUID userId = UUID.randomUUID();
		UUID ppId = UUID.randomUUID();
		UUID instrumentId = UUID.randomUUID();
		
		var dto = new MusicianProfileSaveRequestDto(
				"Stage",
				"Bio",
				ppId,
				"ig",
				"yt",
				"sc",
				"embed123",
				"artist123",
				Set.of(instrumentId),
				List.of(),
				List.of()
		);
		
		var user = User.builder().id(userId).build();
		when(personalProfileTypePolicy.lockAndAssertCanAcquire(userId, RoleEnum.ROLE_MUSICIAN))
				.thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		
		var instrument = Instrument.builder().name("Guitar").build();
		when(instrumentRepo.findAllById(any())).thenReturn(List.of(instrument));
		
		UUID profileId = UUID.randomUUID();
		var saved = MusicianProfile.builder().id(profileId).user(user).build();
		when(repo.save(any(MusicianProfile.class))).thenReturn(saved);
		
		var baseDto = new MusicianProfileResponseDto(
				profileId,
				userId,
				"musician",
				"Stage",
				"Bio",
				ppId,
				null,
				"ig",
				"yt",
				"sc",
				"embed123",
				"artist123",
				Set.of("Guitar"),
				Set.of(),
				null, // bands ignore -> null kabul
				List.of(),
				List.of()
		);
		when(mapper.toDto(saved)).thenReturn(baseDto);
		
		when(bandService.getBandsByUser(userId)).thenReturn(Collections.emptyList());
		
		var result = service.createProfile(userId, dto);
		
		assertThat(result.stageName()).isEqualTo("Stage");
		assertThat(result.bio()).isEqualTo("Bio");
		assertThat(result.bands()).isEmpty();
		
		verify(repo).save(any(MusicianProfile.class));
	}
	
	@Test
	void createProfile_shouldThrow_whenAlreadyExists() {
		UUID userId = UUID.randomUUID();
		
		when(personalProfileTypePolicy.lockAndAssertCanAcquire(userId, RoleEnum.ROLE_MUSICIAN))
				.thenReturn(User.builder().id(userId).build());
		when(repo.findByUserId(userId)).thenReturn(Optional.of(new MusicianProfile()));
		
		assertThatThrownBy(() -> service.createProfile(userId, mock(MusicianProfileSaveRequestDto.class)))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.PROFILE_ALREADY_EXISTS));
		
		verify(repo, never()).save(any());
	}
	
	@Test
	void getProfileByUserId_shouldReturn_whenExists() {
		UUID userId = UUID.randomUUID();
		
		var user = User.builder().id(userId).build();
		var profileId = UUID.randomUUID();
		var profile = MusicianProfile.builder().id(profileId).user(user).build();
		
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.of(profile));
		
		var baseDto = new MusicianProfileResponseDto(
				profileId,
				userId,
				"musician",
				"Stage",
				"Bio",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				null,
				List.of(),
				List.of()
		);
		when(mapper.toDto(profile)).thenReturn(baseDto);
		when(bandService.getBandsByUser(userId)).thenReturn(Collections.emptyList());
		
		var result = service.getProfileByUserId(userId);
		
		assertThat(result.id()).isEqualTo(profileId);
		assertThat(result.bands()).isEmpty();
	}
	
	@Test
	void getProfileByUserId_shouldThrow_whenNotFound() {
		UUID userId = UUID.randomUUID();
		
		when(userFinder.getUser(userId)).thenReturn(User.builder().id(userId).build());
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.getProfileByUserId(userId))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.PROFILE_NOT_FOUND));
	}
	
	@Test
	void updateProfile_shouldPatchAndReturn() {
		UUID userId = UUID.randomUUID();
		var user = User.builder().id(userId).build();
		
		var profileId = UUID.randomUUID();
		var profile = MusicianProfile.builder().id(profileId).user(user).build();
		
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));
		
		when(repo.save(any(MusicianProfile.class))).thenAnswer(inv -> inv.getArgument(0));
		
		UUID ppId = UUID.randomUUID();
		var dto = new MusicianProfileSaveRequestDto(
				"NewStage",
				"NewBio",
				ppId,
				"ig",
				"yt",
				"sc",
				"embedX",
				"artistX",
				Set.of(),
				List.of(),
				List.of()
		);
		
		var baseDto = new MusicianProfileResponseDto(
				profileId,
				userId,
				"musician",
				"NewStage",
				"NewBio",
				ppId,
				null,
				"ig",
				"yt",
				"sc",
				"embedX",
				"artistX",
				Set.of(),
				Set.of(),
				null,
				List.of(),
				List.of()
		);
		
		when(mapper.toDto(any(MusicianProfile.class))).thenReturn(baseDto);
		when(bandService.getBandsByUser(userId)).thenReturn(Collections.emptyList());
		
		var result = service.updateProfile(userId, dto);
		
		assertThat(result.stageName()).isEqualTo("NewStage");
		assertThat(result.bio()).isEqualTo("NewBio");
		assertThat(result.profilePictureMediaId()).isEqualTo(ppId);
		assertThat(result.bands()).isEmpty();
		
		verify(repo).save(any(MusicianProfile.class));
	}
}
