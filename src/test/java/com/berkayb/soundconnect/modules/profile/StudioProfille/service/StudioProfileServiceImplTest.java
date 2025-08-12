package com.berkayb.soundconnect.modules.profile.StudioProfille.service;

import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.mapper.StudioProfileMapper;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileServiceImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("service")
class StudioProfileServiceImplTest {
	
	@Mock private StudioProfileRepository repository;
	@Mock private UserEntityFinder userEntityFinder;
	@Mock private StudioProfileMapper mapper;
	
	@InjectMocks
	private StudioProfileServiceImpl service;
	
	private UUID userId;
	private User user;
	
	@BeforeEach
	void setUp() {
		userId = UUID.randomUUID();
		user = new User();
		user.setId(userId);
	}
	
	private StudioProfileSaveRequestDto sampleReq() {
		return new StudioProfileSaveRequestDto(
				"My Studio",
				"Great rooms",     // descpriction (DTO'da yazım böyle)
				"pp.png",
				"Main Ave 42",     // adress (DTO'da yazım böyle)
				"555-123",
				"studio.com",
				new HashSet<>(List.of("Piano","Drums")),
				"insta.com/x",
				"youtube.com/y"
		);
	}
	
	@Test
	void createProfile_ok() {
		var req = sampleReq();
		
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserId(userId)).thenReturn(Optional.empty());
		
		var saved = new StudioProfile();
		saved.setId(UUID.randomUUID());
		
		when(repository.save(any(StudioProfile.class))).thenReturn(saved);
		
		var resp = new StudioProfileResponseDto(
				saved.getId(),
				req.name(),
				req.descpriction(),
				req.profilePicture(),
				req.adress(),
				req.phone(),
				req.website(),
				req.facilities(),
				req.instagramUrl(),
				req.youtubeUrl()
		);
		when(mapper.toDto(saved)).thenReturn(resp);
		
		var result = service.createProfile(userId, req);
		
		assertThat(result).isEqualTo(resp);
		verify(repository).save(any(StudioProfile.class));
	}
	
	@Test
	void createProfile_should_throw_when_duplicate() {
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserId(userId)).thenReturn(Optional.of(new StudioProfile()));
		
		assertThatThrownBy(() -> service.createProfile(userId, sampleReq()))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.PROFILE_ALREADY_EXISTS));
	}
	
	@Test
	void getProfileByUserId_ok() {
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		
		var profile = new StudioProfile();
		profile.setId(UUID.randomUUID());
		when(repository.findByUserId(userId)).thenReturn(Optional.of(profile));
		
		var resp = new StudioProfileResponseDto(
				profile.getId(), "Name", "Desc", "pp.png",
				"Addr", "555", "site", Set.of("A"), "ig", "yt"
		);
		when(mapper.toDto(profile)).thenReturn(resp);
		
		var result = service.getProfileByUserId(userId);
		
		assertThat(result).isEqualTo(resp);
		verify(repository).findByUserId(userId);
	}
	
	@Test
	void getProfileByUserId_should_throw_when_not_found() {
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserId(userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.getProfileByUserId(userId))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.PROFILE_NOT_FOUND));
	}
	
	@Test
	void updateProfile_ok() {
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		
		var existing = new StudioProfile();
		existing.setId(UUID.randomUUID());
		when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));
		
		var req = sampleReq();
		
		var saved = new StudioProfile();
		saved.setId(existing.getId());
		when(repository.save(any(StudioProfile.class))).thenReturn(saved);
		
		var resp = new StudioProfileResponseDto(
				saved.getId(),
				req.name(), req.descpriction(), req.profilePicture(),
				req.adress(), req.phone(), req.website(),
				req.facilities(), req.instagramUrl(), req.youtubeUrl()
		);
		when(mapper.toDto(saved)).thenReturn(resp);
		
		var result = service.updateProfile(userId, req);
		
		assertThat(result).isEqualTo(resp);
		verify(repository).save(any(StudioProfile.class));
	}
	
	@Test
	void updateProfile_should_throw_when_not_found() {
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserId(userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.updateProfile(userId, sampleReq()))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.PROFILE_NOT_FOUND));
	}
}