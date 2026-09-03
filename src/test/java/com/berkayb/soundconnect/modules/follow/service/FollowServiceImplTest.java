package com.berkayb.soundconnect.modules.follow.service;

import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.follow.event.FollowNotificationRequestedEvent;
import com.berkayb.soundconnect.modules.follow.repository.FollowRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Arrays;
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
	private ApplicationEventPublisher applicationEventPublisher;

	@Mock
	private ListenerVisibilityPolicy listenerVisibilityPolicy;

	@Mock
	private ListenerProfileChoiceStatusReader listenerProfileChoiceStatusReader;
	
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
	void follow_rejects_ghost_target_before_reading_or_writing_follow_graph() {
		when(listenerVisibilityPolicy.lockAndIsPubliclyRestricted(following.getId())).thenReturn(true);

		assertThatThrownBy(() -> sut.follow(follower, following))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.GHOST_PROFILE_CANNOT_BE_FOLLOWED.getMessage());

		verify(listenerVisibilityPolicy).lockAndIsPubliclyRestricted(following.getId());
		verifyNoInteractions(followRepository, applicationEventPublisher);
	}

	@Test
	void follow_rejects_listener_whose_visibility_choice_is_still_pending() {
		when(listenerProfileChoiceStatusReader.requiresChoice(following)).thenReturn(true);

		assertThatThrownBy(() -> sut.follow(follower, following))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.PROFILE_NOT_FOUND));

		verify(listenerProfileChoiceStatusReader).requiresChoice(following);
		verifyNoInteractions(listenerVisibilityPolicy, followRepository, applicationEventPublisher);
	}

	@Test
	void follow_checks_only_locked_target_visibility_before_insert_so_outgoing_follows_remain_allowed() {
		when(listenerVisibilityPolicy.lockAndIsPubliclyRestricted(following.getId())).thenReturn(false);
		when(followRepository.existsByFollowerAndFollowing(follower, following)).thenReturn(false);
		when(followRepository.save(any(Follow.class))).thenAnswer(invocation -> invocation.getArgument(0));

		sut.follow(follower, following);

		InOrder order = inOrder(listenerVisibilityPolicy, followRepository);
		order.verify(listenerVisibilityPolicy).lockAndIsPubliclyRestricted(following.getId());
		order.verify(followRepository).existsByFollowerAndFollowing(follower, following);
		order.verify(followRepository).save(any(Follow.class));
		verifyNoMoreInteractions(listenerVisibilityPolicy);
	}

	@Test
	void follow_queues_an_ids_only_notification_request() {
		when(followRepository.existsByFollowerAndFollowing(follower, following)).thenReturn(false);
		when(followRepository.save(any(Follow.class))).thenAnswer(inv -> inv.getArgument(0));
		ArgumentCaptor<FollowNotificationRequestedEvent> eventCaptor =
				ArgumentCaptor.forClass(FollowNotificationRequestedEvent.class);

		sut.follow(follower, following);

		InOrder order = inOrder(followRepository, applicationEventPublisher);
		order.verify(followRepository).save(any(Follow.class));
		order.verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().followerId()).isEqualTo(follower.getId());
		assertThat(eventCaptor.getValue().followingId()).isEqualTo(following.getId());
		assertThat(Arrays.stream(FollowNotificationRequestedEvent.class.getRecordComponents())
				.map(component -> component.getType().getName())
				.toList())
				.containsExactly(UUID.class.getName(), UUID.class.getName());
	}

	@Test
	void follow_notification_registration_failure_does_not_rollback_domain_work() {
		when(followRepository.existsByFollowerAndFollowing(follower, following)).thenReturn(false);
		when(followRepository.save(any(Follow.class))).thenAnswer(inv -> inv.getArgument(0));
		doThrow(new IllegalStateException("event infrastructure unavailable"))
				.when(applicationEventPublisher).publishEvent(any(FollowNotificationRequestedEvent.class));

		assertThatCode(() -> sut.follow(follower, following)).doesNotThrowAnyException();

		verify(followRepository).save(any(Follow.class));
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
	void getFollowingVisibleTo_rejects_non_owner_when_subject_is_ghost() {
		UUID viewerId = UUID.randomUUID();
		when(listenerVisibilityPolicy.lockForReadAndIsPubliclyRestricted(follower.getId())).thenReturn(true);

		assertThatThrownBy(() -> sut.getFollowingVisibleTo(viewerId, follower))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.FOLLOW_GRAPH_PRIVATE.getMessage());

		verify(listenerVisibilityPolicy).lockForReadAndIsPubliclyRestricted(follower.getId());
		verifyNoInteractions(followRepository);
	}

	@Test
	void getFollowingVisibleTo_allows_owner_after_locking_visibility_snapshot() {
		when(followRepository.findAllByFollower(follower)).thenReturn(List.of());

		assertThat(sut.getFollowingVisibleTo(follower.getId(), follower)).isEmpty();

		verify(followRepository).findAllByFollower(follower);
		verify(listenerVisibilityPolicy).lockForReadAndIsPubliclyRestricted(follower.getId());
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
	void isFollowingVisibleTo_rejects_a_follower_id_other_than_the_viewer() {
		assertThatThrownBy(() -> sut.isFollowingVisibleTo(follower, UUID.randomUUID(), following))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.FOLLOW_RELATION_QUERY_FORBIDDEN.getMessage());

		verifyNoInteractions(followRepository);
	}

	@Test
	void isFollowingVisibleTo_accepts_omitted_legacy_follower_id() {
		when(followRepository.existsByFollowerAndFollowing(follower, following)).thenReturn(true);

		assertThat(sut.isFollowingVisibleTo(follower, null, following)).isTrue();

		verify(followRepository).existsByFollowerAndFollowing(follower, following);
	}

	@Test
	void isFollowingVisibleTo_returns_false_for_ghost_target_without_reading_graph() {
		when(listenerVisibilityPolicy.lockForReadAndIsPubliclyRestricted(following.getId())).thenReturn(true);

		assertThat(sut.isFollowingVisibleTo(follower, null, following)).isFalse();

		verifyNoInteractions(followRepository);
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

	@Test
	void countFollowersVisibleTo_rejects_non_owner_when_subject_is_ghost() {
		UUID viewerId = UUID.randomUUID();
		when(listenerVisibilityPolicy.lockForReadAndIsPubliclyRestricted(following.getId())).thenReturn(true);

		assertThatThrownBy(() -> sut.countFollowersVisibleTo(viewerId, following))
				.isInstanceOf(SoundConnectException.class)
				.hasMessageContaining(ErrorType.FOLLOW_GRAPH_PRIVATE.getMessage());

		verifyNoInteractions(followRepository);
	}
}
