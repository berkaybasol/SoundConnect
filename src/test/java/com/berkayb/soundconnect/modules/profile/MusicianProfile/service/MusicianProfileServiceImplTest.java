package com.berkayb.soundconnect.modules.profile.MusicianProfile.service;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.mapper.MusicianProfileMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class MusicianProfileServiceImplTest {
	
	@Mock MusicianProfileRepository repo;
	@Mock UserEntityFinder userFinder;
	@Mock InstrumentRepository instrumentRepo;
	@Mock MusicianProfileMapper mapper;
	
	@InjectMocks
	MusicianProfileServiceImpl service;
	
	@Test
	void createProfile_shouldCreate_whenNotExists() {
		UUID userId = UUID.randomUUID();
		var dto = new MusicianProfileSaveRequestDto(
				"Stage", "Bio", "pic", "ig", "yt", "sc", "sp",
				Set.of(UUID.randomUUID())
		);
		
		var user = User.builder().id(userId).build();
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		
		var instrument = Instrument.builder().name("Guitar").build();
		when(instrumentRepo.findAllById(any())).thenReturn(List.of(instrument));
		
		var saved = MusicianProfile.builder().id(UUID.randomUUID()).user(user).build();
		when(repo.save(any(MusicianProfile.class))).thenReturn(saved);
		
		var resp = new MusicianProfileResponseDto(saved.getId(), "Stage", "Bio", "pic","ig","yt","sc","sp",
		                                          Set.of("Guitar"), Collections.emptySet());
		when(mapper.toDto(saved)).thenReturn(resp);
		
		var result = service.createProfile(userId, dto);
		
		assertThat(result.stageName()).isEqualTo("Stage");
		verify(repo).save(any(MusicianProfile.class));
	}
	
	@Test
	void createProfile_shouldThrow_whenAlreadyExists() {
		UUID userId = UUID.randomUUID();
		when(userFinder.getUser(userId)).thenReturn(User.builder().id(userId).build());
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
		var profile = MusicianProfile.builder().id(UUID.randomUUID()).build();
		when(userFinder.getUser(userId)).thenReturn(User.builder().id(userId).build());
		when(repo.findByUserId(userId)).thenReturn(Optional.of(profile));
		
		var resp = new MusicianProfileResponseDto(profile.getId(), "S", "B", null,null,null,null,null,
		                                          Collections.emptySet(), Collections.emptySet());
		when(mapper.toDto(profile)).thenReturn(resp);
		
		var result = service.getProfileByUserId(userId);
		assertThat(result.id()).isEqualTo(profile.getId());
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
		var profile = MusicianProfile.builder().id(UUID.randomUUID()).build();
		when(userFinder.getUser(userId)).thenReturn(User.builder().id(userId).build());
		when(repo.findByUserId(userId)).thenReturn(Optional.of(profile));
		
		var dto = new MusicianProfileSaveRequestDto("NewStage", "NewBio", "pp", "ig","yt","sc","sp", Set.of());
		when(instrumentRepo.findAllById(any())).thenReturn(List.of());
		when(repo.save(profile)).thenReturn(profile);
		
		var resp = new MusicianProfileResponseDto(profile.getId(), "NewStage", "NewBio", "pp","ig","yt","sc","sp",
		                                          Collections.emptySet(), Collections.emptySet());
		when(mapper.toDto(profile)).thenReturn(resp);
		
		var result = service.updateProfile(userId, dto);
		assertThat(result.stageName()).isEqualTo("NewStage");
		assertThat(result.bio()).isEqualTo("NewBio");
		verify(repo).save(profile);
	}
}