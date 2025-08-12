package com.berkayb.soundconnect.modules.profile.ProducerProfile.service;

import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.request.ProducerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.response.ProducerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.entity.ProducerProfile;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.mapper.ProducerProfileMapper;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.repository.ProducerProfileRepository;
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
class ProducerProfileServiceImplTest {
	
	@Mock ProducerProfileRepository repo;
	@Mock UserEntityFinder userFinder;
	@Mock ProducerProfileMapper mapper;
	
	@InjectMocks ProducerProfileServiceImpl service;
	
	private UUID userId;
	private User user;
	
	@BeforeEach
	void init() {
		userId = UUID.randomUUID();
		user = User.builder().id(userId).username("x").password("p").build();
	}
	
	@Test
	void createProfile_ok() {
		ProducerProfileSaveRequestDto dto = new ProducerProfileSaveRequestDto(
				"Prod","desc","pp.png","addr","555","site.com","ig","yt"
		);
		
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		
		ProducerProfile saved = ProducerProfile.builder()
		                                       .id(UUID.randomUUID())
		                                       .user(user)
		                                       .name("Prod")
		                                       .description("desc")
		                                       .profilePicture("pp.png")
		                                       .address("addr")
		                                       .phone("555")
		                                       .website("site.com")
		                                       .instagramUrl("ig")
		                                       .youtubeUrl("yt")
		                                       .build();
		
		when(repo.save(any(ProducerProfile.class))).thenReturn(saved);
		ProducerProfileResponseDto resp = new ProducerProfileResponseDto(
				saved.getId(),"Prod","desc","pp.png","addr","555","site.com","ig","yt"
		);
		when(mapper.toDto(saved)).thenReturn(resp);
		
		ProducerProfileResponseDto out = service.createProfile(userId, dto);
		
		assertThat(out).isEqualTo(resp);
		
		ArgumentCaptor<ProducerProfile> cap = ArgumentCaptor.forClass(ProducerProfile.class);
		verify(repo).save(cap.capture());
		assertThat(cap.getValue().getWebsite()).isEqualTo("site.com");
		assertThat(cap.getValue().getName()).isEqualTo("Prod");
		assertThat(cap.getValue().getDescription()).isEqualTo("desc");
	}
	
	@Test
	void createProfile_should_throw_when_duplicate() {
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.of(ProducerProfile.builder().build()));
		
		assertThatThrownBy(() -> service.createProfile(userId,
		                                               new ProducerProfileSaveRequestDto("n","d","p","a","5","s","ig","yt")))
				.isInstanceOfSatisfying(SoundConnectException.class,
				                        ex -> assertThat(ex.getErrorType()).isEqualTo(ErrorType.PROFILE_ALREADY_EXISTS));
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
		ProducerProfile existing = ProducerProfile.builder()
		                                          .id(UUID.randomUUID())
		                                          .user(user)
		                                          .name("Old")
		                                          .description("old")
		                                          .profilePicture("old.png")
		                                          .address("oldaddr")
		                                          .phone("000")
		                                          .website("oldsite.com")
		                                          .instagramUrl("ig0")
		                                          .youtubeUrl("yt0")
		                                          .build();
		
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.of(existing));
		
		ProducerProfileSaveRequestDto dto = new ProducerProfileSaveRequestDto(
				"New","new","new.png","addr2","111","site2.com","ig2","yt2"
		);
		
		ProducerProfile updated = ProducerProfile.builder()
		                                         .id(existing.getId())
		                                         .user(user)
		                                         .name("New")
		                                         .description("new")
		                                         .profilePicture("new.png")
		                                         .address("addr2")
		                                         .phone("111")
		                                         .website("site2.com")
		                                         .instagramUrl("ig2")
		                                         .youtubeUrl("yt2")
		                                         .build();
		
		when(repo.save(any(ProducerProfile.class))).thenReturn(updated);
		ProducerProfileResponseDto resp = new ProducerProfileResponseDto(
				updated.getId(),"New","new","new.png","addr2","111","site2.com","ig2","yt2"
		);
		when(mapper.toDto(updated)).thenReturn(resp);
		
		var out = service.updateProfile(userId, dto);
		
		assertThat(out).isEqualTo(resp);
		verify(repo).save(any(ProducerProfile.class));
	}
}