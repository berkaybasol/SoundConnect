package com.berkayb.soundconnect.modules.follow.event;

import com.berkayb.soundconnect.modules.follow.band.event.BandFollowNotificationRequestedEvent;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowNotificationEventListenerTest {
	@Mock NotificationProducer notificationProducer;
	@Mock GhostListenerIdentityBatchResolver ghostIdentityBatchResolver;
	@Mock UserEntityFinder userEntityFinder;
	@Mock PublicProfileResolverService publicProfileResolverService;
	@Mock BandEntityFinder bandEntityFinder;
	@InjectMocks FollowNotificationEventListener listener;

	@Test
	void directFollowGhostUsesCanonicalUsernameAndDoesNotFallbackWhenListenerAvatarIsNull() {
		UUID followerId = UUID.randomUUID();
		UUID followingId = UUID.randomUUID();
		when(ghostIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of(
				followerId,
				new GhostListenerIdentity(
						followerId,
						"listener_handle",
						null,
						ListenerVisibilityMode.GHOST
				)
		));
		ArgumentCaptor<NotificationInboundEvent> captor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);

		listener.onFollowNotificationRequested(
				new FollowNotificationRequestedEvent(followerId, followingId));

		verify(notificationProducer).publish(captor.capture());
		NotificationInboundEvent notification = captor.getValue();
		assertThat(notification.recipientId()).isEqualTo(followingId);
		assertThat(notification.type()).isEqualTo(NotificationType.SOCIAL_NEW_FOLLOWER);
		assertThat(notification.title()).isEqualTo("listener_handle seni takip etmeye başladı");
		assertThat(notification.payload())
				.containsEntry("followerId", followerId.toString())
				.containsEntry("followerUsername", "listener_handle")
				.containsEntry("followerVisibilityMode", "GHOST")
				.doesNotContainKey("followerAvatarUrl");
		verifyNoInteractions(userEntityFinder, publicProfileResolverService, bandEntityFinder);
	}

	@Test
	void directFollowStandardVenueKeepsVenueTitleAndProfileAvatarBehavior() {
		UUID followerId = UUID.randomUUID();
		UUID followingId = UUID.randomUUID();
		User follower = User.builder()
				.id(followerId)
				.username("owner_username")
				.profilePicture("user-avatar.jpg")
				.build();
		when(ghostIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of());
		when(userEntityFinder.getUser(followerId)).thenReturn(follower);
		when(publicProfileResolverService.resolveByUserId(followerId)).thenReturn(
				new UserProfilesResolveResponseDto(followerId, List.of(
						new UserProfileTargetDto(
								"VENUE",
								UUID.randomUUID(),
								"Karga Sahne",
								"venue-avatar.jpg"
						)
				))
		);
		ArgumentCaptor<NotificationInboundEvent> captor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);

		listener.onFollowNotificationRequested(
				new FollowNotificationRequestedEvent(followerId, followingId));

		verify(notificationProducer).publish(captor.capture());
		assertThat(captor.getValue().title()).isEqualTo("Karga Sahne seni takip etmeye başladı");
		assertThat(captor.getValue().payload())
				.containsEntry("followerUsername", "owner_username")
				.containsEntry("followerAvatarUrl", "venue-avatar.jpg")
				.doesNotContainKey("followerVisibilityMode");
	}

	@Test
	void bandFollowGhostUsesExactListenerIdentityForEveryRecipient() {
		UUID followerId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		UUID firstRecipient = UUID.randomUUID();
		UUID secondRecipient = UUID.randomUUID();
		when(ghostIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of(
				followerId,
				new GhostListenerIdentity(
						followerId,
						"listener_handle",
						"listener-avatar.jpg",
						ListenerVisibilityMode.GHOST
				)
		));
		when(bandEntityFinder.getBand(bandId))
				.thenReturn(Band.builder().id(bandId).name("The Band").build());
		ArgumentCaptor<NotificationInboundEvent> captor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);

		listener.onBandFollowNotificationRequested(new BandFollowNotificationRequestedEvent(
				followerId,
				bandId,
				List.of(firstRecipient, secondRecipient)
		));

		verify(notificationProducer, times(2)).publish(captor.capture());
		assertThat(captor.getAllValues())
				.extracting(NotificationInboundEvent::recipientId)
				.containsExactly(firstRecipient, secondRecipient);
		assertThat(captor.getAllValues()).allSatisfy(notification -> {
			assertThat(notification.title()).isEqualTo("listener_handle bandını takip etmeye başladı");
			assertThat(notification.message()).isEqualTo("The Band yeni bir takipçi kazandı.");
			assertThat(notification.payload())
					.containsEntry("followerUsername", "listener_handle")
					.containsEntry("followerAvatarUrl", "listener-avatar.jpg")
					.containsEntry("followerVisibilityMode", "GHOST")
					.containsEntry("bandId", bandId.toString());
		});
		verifyNoInteractions(userEntityFinder, publicProfileResolverService);
	}

	@Test
	void bandFollowStandardKeepsUsernameTitleAndUserAvatarFallback() {
		UUID followerId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		UUID recipientId = UUID.randomUUID();
		User follower = User.builder()
				.id(followerId)
				.username("standard_user")
				.profilePicture("user-avatar.jpg")
				.build();
		when(ghostIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of());
		when(userEntityFinder.getUser(followerId)).thenReturn(follower);
		when(publicProfileResolverService.resolveByUserId(followerId))
				.thenReturn(new UserProfilesResolveResponseDto(followerId, List.of()));
		when(bandEntityFinder.getBand(bandId))
				.thenReturn(Band.builder().id(bandId).name("The Band").build());
		ArgumentCaptor<NotificationInboundEvent> captor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);

		listener.onBandFollowNotificationRequested(new BandFollowNotificationRequestedEvent(
				followerId, bandId, List.of(recipientId)));

		verify(notificationProducer).publish(captor.capture());
		assertThat(captor.getValue().title()).isEqualTo("standard_user bandını takip etmeye başladı");
		assertThat(captor.getValue().payload())
				.containsEntry("followerUsername", "standard_user")
				.containsEntry("followerAvatarUrl", "user-avatar.jpg")
				.doesNotContainKey("followerVisibilityMode");
	}

	@Test
	void ghostResolverFailureSkipsNotificationWithoutLegacyIdentityFallback() {
		UUID followerId = UUID.randomUUID();
		doThrow(new IllegalStateException("identity repository unavailable"))
				.when(ghostIdentityBatchResolver).resolve(anyCollection());

		assertThatCode(() -> listener.onFollowNotificationRequested(
				new FollowNotificationRequestedEvent(followerId, UUID.randomUUID())))
				.doesNotThrowAnyException();

		verify(notificationProducer, never()).publish(org.mockito.ArgumentMatchers.any());
		verifyNoInteractions(userEntityFinder, publicProfileResolverService, bandEntityFinder);
	}

	@Test
	void handlersRunAfterCommitInFreshTransactions() throws Exception {
		assertAfterCommitWithFreshTransaction(
				"onFollowNotificationRequested", FollowNotificationRequestedEvent.class);
		assertAfterCommitWithFreshTransaction(
				"onBandFollowNotificationRequested", BandFollowNotificationRequestedEvent.class);
	}

	private void assertAfterCommitWithFreshTransaction(String methodName, Class<?> eventType) throws Exception {
		var method = FollowNotificationEventListener.class.getMethod(methodName, eventType);
		TransactionalEventListener listenerAnnotation = method.getAnnotation(TransactionalEventListener.class);
		Transactional transaction = method.getAnnotation(Transactional.class);

		assertThat(listenerAnnotation).isNotNull();
		assertThat(listenerAnnotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
		assertThat(transaction).isNotNull();
		assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
		assertThat(transaction.readOnly()).isFalse();
	}
}
