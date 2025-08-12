package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ListenerProfileServiceImplTest {
	
	@Mock ListenerProfileRepository repo;
	@Mock UserEntityFinder userFinder;
	@Mock ListenerProfileMapper mapper;
	
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
		ListenerSaveRequestDto dto = new ListenerSaveRequestDto("hello", "pp.png");
		
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		
		ListenerProfile saved = ListenerProfile.builder()
		                                       .id(UUID.randomUUID())
		                                       .user(user)
		                                       .description("hello")
		                                       .profilePicture("pp.png")
		                                       .build();
		
		when(repo.save(any(ListenerProfile.class))).thenReturn(saved);
		ListenerProfileResponseDto resp = new ListenerProfileResponseDto(saved.getId(), "hello", "pp.png", userId);
		when(mapper.toDto(saved)).thenReturn(resp);
		
		ListenerProfileResponseDto out = service.createProfile(userId, dto);
		
		assertThat(out).isEqualTo(resp);
		
		ArgumentCaptor<ListenerProfile> cap = ArgumentCaptor.forClass(ListenerProfile.class);
		verify(repo).save(cap.capture());
		assertThat(cap.getValue().getDescription()).isEqualTo("hello");
		assertThat(cap.getValue().getProfilePicture()).isEqualTo("pp.png");
	}
	
	@Test
	void createProfile_should_throw_when_duplicate() {
		// given
		UUID userId = UUID.randomUUID();
		when(userFinder.getUser(userId)).thenReturn(User.builder().id(userId).build());
		when(repo.findByUserId(userId))
				.thenReturn(Optional.of(ListenerProfile.builder().build()));
		
		// when + then
		assertThatThrownBy(() -> service.createProfile(userId, new ListenerSaveRequestDto("desc","pic")))
				.isInstanceOfSatisfying(SoundConnectException.class, ex ->
						assertThat(ex.getErrorType()).isEqualTo(ErrorType.PROFILE_ALREADY_EXISTS)
				);
	}
	
	@Test
	void getProfileByUserId_should_throw_when_not_found() {
		// given
		UUID userId = UUID.randomUUID();
		when(userFinder.getUser(userId)).thenReturn(User.builder().id(userId).build());
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		
		// when + then
		assertThatThrownBy(() -> service.getProfileByUserId(userId))
				.isInstanceOfSatisfying(SoundConnectException.class, ex ->
						assertThat(ex.getErrorType()).isEqualTo(ErrorType.PROFILE_NOT_FOUND)
				);
	}
	
	@Test
	void updateProfile_ok() {
		ListenerProfile existing = ListenerProfile.builder()
		                                          .id(UUID.randomUUID()).user(user)
		                                          .description("old").profilePicture("old.png").build();
		
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.of(existing));
		
		ListenerSaveRequestDto dto = new ListenerSaveRequestDto("new-bio", "new.png");
		
		ListenerProfile updated = ListenerProfile.builder()
		                                         .id(existing.getId()).user(user)
		                                         .description("new-bio").profilePicture("new.png").build();
		
		when(repo.save(any(ListenerProfile.class))).thenReturn(updated);
		ListenerProfileResponseDto resp = new ListenerProfileResponseDto(updated.getId(), "new-bio", "new.png", userId);
		when(mapper.toDto(updated)).thenReturn(resp);
		
		ListenerProfileResponseDto out = service.updateProfile(userId, dto);
		
		assertThat(out).isEqualTo(resp);
		verify(repo).save(any(ListenerProfile.class));
	}
}