package com.berkayb.soundconnect.modules.profile.OrganizerProfile.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.request.OrganizerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.response.OrganizerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity.OrganizerProfile;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.mapper.OrganizerProfileMapper;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository.OrganizerProfileRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("service")
class OrganizerProfileServiceImplTest {
	
	@Mock OrganizerProfileRepository repo;
	@Mock UserEntityFinder userFinder;
	@Mock OrganizerProfileMapper mapper;
	@Mock MediaAssetService mediaAssetService;
	
	@InjectMocks OrganizerProfileServiceImpl service;
	
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
		
		OrganizerProfileSaveRequestDto dto = new OrganizerProfileSaveRequestDto(
				"Org", "desc", ppId, "addr", "555", "ig", "yt"
		);
		
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		
		OrganizerProfile saved = OrganizerProfile.builder()
		                                         .id(UUID.randomUUID())
		                                         .user(user)
		                                         .name("Org")
		                                         .description("desc")
		                                         .profilePictureMediaId(ppId)
		                                         .address("addr")
		                                         .phone("555")
		                                         .instagramUrl("ig")
		                                         .youtubeUrl("yt")
		                                         .build();
		
		when(repo.save(any(OrganizerProfile.class))).thenReturn(saved);
		
		OrganizerProfileResponseDto resp = new OrganizerProfileResponseDto(
				saved.getId(),
				"Org",
				"desc",
				ppId,
				"addr",
				"555",
				"ig",
				"yt"
		);
		when(mapper.toDto(saved)).thenReturn(resp);
		
		OrganizerProfileResponseDto out = service.createProfile(userId, dto);
		
		assertThat(out).isEqualTo(resp);
		
		ArgumentCaptor<OrganizerProfile> cap = ArgumentCaptor.forClass(OrganizerProfile.class);
		verify(repo).save(cap.capture());
		
		OrganizerProfile captured = cap.getValue();
		assertThat(captured.getName()).isEqualTo("Org");
		assertThat(captured.getDescription()).isEqualTo("desc");
		assertThat(captured.getProfilePictureMediaId()).isEqualTo(ppId); // ✅ aynı UUID
		assertThat(captured.getAddress()).isEqualTo("addr");
		assertThat(captured.getPhone()).isEqualTo("555");
		assertThat(captured.getInstagramUrl()).isEqualTo("ig");
		assertThat(captured.getYoutubeUrl()).isEqualTo("yt");
	}
	
	@Test
	void createProfile_should_throw_when_duplicate() {
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.of(OrganizerProfile.builder().build()));
		
		assertThatThrownBy(() -> service.createProfile(
				userId,
				new OrganizerProfileSaveRequestDto("n", "d", UUID.randomUUID(), "a", "5", "ig", "yt")
		))
				.isInstanceOfSatisfying(SoundConnectException.class,
				                        ex -> assertThat(ex.getErrorType()).isEqualTo(ErrorType.PROFILE_ALREADY_EXISTS));
		
		verify(repo, never()).save(any());
	}
	
	@Test
	void getProfileByUserId_should_throw_when_not_found() {
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.getProfileByUserId(userId))
				.isInstanceOfSatisfying(SoundConnectException.class,
				                        ex -> assertThat(ex.getErrorType()).isEqualTo(ErrorType.PROFILE_NOT_FOUND));
	}
	
	@Test
	void updateProfile_ok() {
		UUID oldPp = UUID.randomUUID();
		
		OrganizerProfile existing = OrganizerProfile.builder()
		                                            .id(UUID.randomUUID())
		                                            .user(user)
		                                            .name("Old")
		                                            .description("old")
		                                            .profilePictureMediaId(oldPp)
		                                            .address("oldaddr")
		                                            .phone("000")
		                                            .instagramUrl("ig0")
		                                            .youtubeUrl("yt0")
		                                            .build();
		
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.of(existing));
		
		UUID newPp = UUID.randomUUID();
		OrganizerProfileSaveRequestDto dto = new OrganizerProfileSaveRequestDto(
				"New", "new", newPp, "addr2", "111", "ig2", "yt2"
		);
		
		// repo.save'e gelen entity patched olacak; biz return için updated üretelim
		OrganizerProfile updated = OrganizerProfile.builder()
		                                           .id(existing.getId())
		                                           .user(user)
		                                           .name("New")
		                                           .description("new")
		                                           .profilePictureMediaId(newPp)
		                                           .address("addr2")
		                                           .phone("111")
		                                           .instagramUrl("ig2")
		                                           .youtubeUrl("yt2")
		                                           .build();
		
		when(repo.save(any(OrganizerProfile.class))).thenReturn(updated);
		
		OrganizerProfileResponseDto resp = new OrganizerProfileResponseDto(
				updated.getId(),
				"New",
				"new",
				newPp,
				"addr2",
				"111",
				"ig2",
				"yt2"
		);
		when(mapper.toDto(updated)).thenReturn(resp);
		
		var out = service.updateProfile(userId, dto);
		
		assertThat(out).isEqualTo(resp);
		verify(repo).save(any(OrganizerProfile.class));
	}
}
