package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.follow.service.FollowService;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.mapper.ListenerProfileMapper;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("service")
class ListenerProfileServiceImplTest {
	
	@Mock ListenerProfileRepository repo;
	@Mock UserEntityFinder userFinder;
	@Mock ListenerProfileMapper mapper;
	@Mock MediaAssetService mediaAssetService;
	@Mock FollowService followService;
	
	@InjectMocks ListenerProfileServiceImpl service;
	
	private UUID userId;
	private User user;
	
	@BeforeEach
	void init() {
		userId = UUID.randomUUID();
		user = User.builder().id(userId).username("x").password("p").build();
	}
	
	@Test
	void createProfile_ok() {
		UUID ppId = UUID.randomUUID();
		ListenerSaveRequestDto dto = new ListenerSaveRequestDto("hello", ppId);
		
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		
		ListenerProfile saved = ListenerProfile.builder()
		                                       .id(UUID.randomUUID())
		                                       .user(user)
		                                       .description("hello")
		                                       .profilePictureMediaId(ppId)
		                                       .build();
		
		when(repo.save(any(ListenerProfile.class))).thenReturn(saved);
		
		ListenerProfileResponseDto resp =
				new ListenerProfileResponseDto(
						saved.getId(), userId, "x", "hello", ppId,
						"https://cdn.example.com/profile.jpg", 0, 0);
		
		when(mapper.toDto(saved)).thenReturn(resp);
		when(mediaAssetService.getDisplayUrl(ppId)).thenReturn("https://cdn.example.com/profile.jpg");
		
		ListenerProfileResponseDto out = service.createProfile(userId, dto);
		
		assertThat(out).isEqualTo(resp);
		verify(mediaAssetService).validateAssignableMedia(
				userId, ppId, MediaOwnerType.USER, userId, MediaKind.IMAGE
		);
		
		ArgumentCaptor<ListenerProfile> cap = ArgumentCaptor.forClass(ListenerProfile.class);
		verify(repo).save(cap.capture());
		assertThat(cap.getValue().getDescription()).isEqualTo("hello");
		assertThat(cap.getValue().getProfilePictureMediaId()).isEqualTo(ppId);
	}
	
	@Test
	void createProfile_should_throw_when_duplicate() {
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.of(ListenerProfile.builder().build()));
		
		assertThatThrownBy(() -> service.createProfile(userId, new ListenerSaveRequestDto("desc", UUID.randomUUID())))
				.isInstanceOfSatisfying(SoundConnectException.class, ex ->
						assertThat(ex.getErrorType()).isEqualTo(ErrorType.PROFILE_ALREADY_EXISTS)
				);
	}
	
	@Test
	void getProfileByUserId_should_throw_when_not_found() {
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.getProfileByUserId(userId))
				.isInstanceOfSatisfying(SoundConnectException.class, ex ->
						assertThat(ex.getErrorType()).isEqualTo(ErrorType.PROFILE_NOT_FOUND)
				);
	}
	
	@Test
	void updateProfile_ok() {
		UUID oldPp = UUID.randomUUID();
		UUID newPp = UUID.randomUUID();
		
		ListenerProfile existing = ListenerProfile.builder()
		                                          .id(UUID.randomUUID())
		                                          .user(user)
		                                          .description("old")
		                                          .profilePictureMediaId(oldPp)
		                                          .build();
		
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.of(existing));
		
		ListenerSaveRequestDto dto = new ListenerSaveRequestDto("new-bio", newPp);
		
		ListenerProfile updated = ListenerProfile.builder()
		                                         .id(existing.getId())
		                                         .user(user)
		                                         .description("new-bio")
		                                         .profilePictureMediaId(newPp)
		                                         .build();
		
		when(repo.save(any(ListenerProfile.class))).thenReturn(updated);
		
		ListenerProfileResponseDto resp =
				new ListenerProfileResponseDto(
						updated.getId(), userId, "x", "new-bio", newPp,
						"https://cdn.example.com/profile.jpg", 0, 0);
		
		when(mapper.toDto(updated)).thenReturn(resp);
		when(mediaAssetService.getDisplayUrl(newPp)).thenReturn("https://cdn.example.com/profile.jpg");
		
		ListenerProfileResponseDto out = service.updateProfile(userId, dto);
		
		assertThat(out).isEqualTo(resp);
		verify(mediaAssetService).validateAssignableMedia(
				userId, newPp, MediaOwnerType.LISTENER_PROFILE, existing.getId(), MediaKind.IMAGE
		);
		verify(repo).save(any(ListenerProfile.class));
	}
}
