package com.berkayb.soundconnect.modules.follow.service;

import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.follow.repository.FollowRepository;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("service")
class FollowServiceImplTest {
	
	@Mock
	private FollowRepository followRepository;

	@Mock
	private NotificationProducer notificationProducer;

	@Mock
	private PublicProfileResolverService publicProfileResolverService;
	
	@InjectMocks
	private FollowServiceImpl sut;
	
	private User follower;
	private User following;
	
	@BeforeEach
	void setUp() {
		follower = User.builder()
		               .id(UUID.randomUUID())
		               .username("follower")
		               .email("f@x.com")
		               .password("{noop}x")
		               .roles(Set.of())
		               .build();
		
		following = User.builder()
		                .id(UUID.randomUUID())
		                .username("following")
		                .email("g@x.com")
		                .password("{noop}x")
		                .roles(Set.of())
		                .build();
	}
	
	// -------- follow() --------
	@Test
	void follow_ok() {
		// not self, not already following
		when(followRepository.existsByFollowerAndFollowing(follower, following)).thenReturn(false);
		when(followRepository.save(any(Follow.class))).thenAnswer(inv -> inv.getArgument(0));
		when(publicProfileResolverService.resolveByUserId(follower.getId()))
				.thenReturn(new UserProfilesResolveResponseDto(follower.getId(), List.of()));
		
		ArgumentCaptor<Follow> captor = ArgumentCaptor.forClass(Follow.class);
		
		sut.follow(follower, following);
		
		verify(followRepository).existsByFollowerAndFollowing(follower, following);
		verify(followRepository).save(captor.capture());
		verifyNoMoreInteractions(followRepository);
		
		Follow saved = captor.getValue();
		assertThat(saved.getFollower()).isSameAs(follower);
		assertThat(saved.getFollowing()).isSameAs(following);
		assertThat(saved.getFollowedAt()).isNotNull();
		assertThat(saved.getFollowedAt()).isBeforeOrEqualTo(LocalDateTime.now());
	}

	@Test
	void follow_notification_uses_venue_name_when_follower_has_venue_profile() {
		when(followRepository.existsByFollowerAndFollowing(follower, following)).thenReturn(false);
		when(followRepository.save(any(Follow.class))).thenAnswer(inv -> inv.getArgument(0));
		when(publicProfileResolverService.resolveByUserId(follower.getId()))
				.thenReturn(new UserProfilesResolveResponseDto(
						follower.getId(),
						List.of(new UserProfileTargetDto("VENUE", UUID.randomUUID(), "Karga Sahne", null))
				));
		ArgumentCaptor<NotificationInboundEvent> eventCaptor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);

		sut.follow(follower, following);

		verify(notificationProducer).publish(eventCaptor.capture());
		assertThat(eventCaptor.getValue().title()).isEqualTo("Karga Sahne seni takip etmeye başladı");
		assertThat(eventCaptor.getValue().payload()).containsEntry("followerUsername", "follower");
	}

	@Test
	void follow_notification_keeps_username_when_follower_profile_is_not_venue() {
		when(followRepository.existsByFollowerAndFollowing(follower, following)).thenReturn(false);
		when(followRepository.save(any(Follow.class))).thenAnswer(inv -> inv.getArgument(0));
		when(publicProfileResolverService.resolveByUserId(follower.getId()))
				.thenReturn(new UserProfilesResolveResponseDto(
						follower.getId(),
						List.of(new UserProfileTargetDto("MUSICIAN", UUID.randomUUID(), "Artist Name", null))
				));
		ArgumentCaptor<NotificationInboundEvent> eventCaptor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);

		sut.follow(follower, following);

		verify(notificationProducer).publish(eventCaptor.capture());
		assertThat(eventCaptor.getValue().title()).isEqualTo("follower seni takip etmeye başladı");
	}
	
	@Test
	void follow_throws_when_self_follow() {
		User me = follower; // aynı referans
		assertThatThrownBy(() -> sut.follow(me, me))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.CANNOT_FOLLOW_SELF.getMessage());
		
		verifyNoInteractions(followRepository);
	}
	
	@Test
	void follow_throws_when_already_following() {
		when(followRepository.existsByFollowerAndFollowing(follower, following)).thenReturn(true);
		
		assertThatThrownBy(() -> sut.follow(follower, following))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.ALREADY_FOLLOWING.getMessage());
		
		verify(followRepository).existsByFollowerAndFollowing(follower, following);
		verifyNoMoreInteractions(followRepository);
	}
	
	// -------- unfollow() --------
	@Test
	void unfollow_ok() {
		Follow rel = Follow.builder().id(UUID.randomUUID()).follower(follower).following(following).followedAt(LocalDateTime.now()).build();
		when(followRepository.findByFollowerAndFollowing(follower, following)).thenReturn(Optional.of(rel));
		doNothing().when(followRepository).delete(rel);
		
		sut.unfollow(follower, following);
		
		verify(followRepository).findByFollowerAndFollowing(follower, following);
		verify(followRepository).delete(rel);
		verifyNoMoreInteractions(followRepository);
	}
	
	@Test
	void unfollow_throws_when_relation_not_found() {
		when(followRepository.findByFollowerAndFollowing(follower, following)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> sut.unfollow(follower, following))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.FOLLOW_RELATION_NOT_FOUND.getMessage());
		
		verify(followRepository).findByFollowerAndFollowing(follower, following);
		verifyNoMoreInteractions(followRepository);
	}
	
	// -------- queries --------
	@Test
	void getFollowing_ok() {
		when(followRepository.findAllByFollower(follower)).thenReturn(List.of(
				Follow.builder().id(UUID.randomUUID()).follower(follower).following(following).followedAt(LocalDateTime.now()).build()
		));
		
		var list = sut.getFollowing(follower);
		assertThat(list).hasSize(1);
		assertThat(list.get(0).getFollower()).isSameAs(follower);
		
		verify(followRepository).findAllByFollower(follower);
		verifyNoMoreInteractions(followRepository);
	}
	
	@Test
	void getFollowers_ok() {
		when(followRepository.findAllByFollowing(following)).thenReturn(List.of(
				Follow.builder().id(UUID.randomUUID()).follower(follower).following(following).followedAt(LocalDateTime.now()).build()
		));
		
		var list = sut.getFollowers(following);
		assertThat(list).hasSize(1);
		assertThat(list.get(0).getFollowing()).isSameAs(following);
		
		verify(followRepository).findAllByFollowing(following);
		verifyNoMoreInteractions(followRepository);
	}
	
	@Test
	void isFollowing_ok_true_false() {
		when(followRepository.existsByFollowerAndFollowing(follower, following)).thenReturn(true, false);
		
		assertThat(sut.isFollowing(follower, following)).isTrue();
		assertThat(sut.isFollowing(follower, following)).isFalse();
		
		verify(followRepository, times(2)).existsByFollowerAndFollowing(follower, following);
		verifyNoMoreInteractions(followRepository);
	}
	
	@Test
	void counts_ok() {
		when(followRepository.countByFollower(follower)).thenReturn(5L);
		when(followRepository.countByFollowing(following)).thenReturn(12L);
		
		assertThat(sut.countFollowing(follower)).isEqualTo(5L);
		assertThat(sut.countFollowers(following)).isEqualTo(12L);
		
		verify(followRepository).countByFollower(follower);
		verify(followRepository).countByFollowing(following);
		verifyNoMoreInteractions(followRepository);
	}
}
