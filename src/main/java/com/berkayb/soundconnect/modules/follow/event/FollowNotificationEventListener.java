package com.berkayb.soundconnect.modules.follow.event;

import com.berkayb.soundconnect.modules.follow.band.event.BandFollowNotificationRequestedEvent;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds follower notification identity only after the relationship commit.
 *
 * <p>The fresh transaction keeps the ghost resolver's shared visibility lock
 * until the broker payload is fully built and queued. A resolver failure skips
 * the best-effort notification instead of falling back to a legacy profile
 * name or avatar.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FollowNotificationEventListener {
	private static final String UNKNOWN_USER = "Bir kullanıcı";
	private static final String UNKNOWN_BAND = "Band";

	private final NotificationProducer notificationProducer;
	private final GhostListenerIdentityBatchResolver ghostIdentityBatchResolver;
	private final UserEntityFinder userEntityFinder;
	private final PublicProfileResolverService publicProfileResolverService;
	private final BandEntityFinder bandEntityFinder;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onFollowNotificationRequested(FollowNotificationRequestedEvent event) {
		if (event == null || event.followerId() == null || event.followingId() == null) return;
		try {
			FollowerNotificationIdentity identity = resolveFollowerIdentity(event.followerId());
			notificationProducer.publish(NotificationInboundEvent.builder()
					.recipientId(event.followingId())
					.type(NotificationType.SOCIAL_NEW_FOLLOWER)
					.title(identity.notificationName() + " seni takip etmeye başladı")
					.message("Yeni bir takipçin var.")
					.payload(followerPayload("NEW_FOLLOWER", event.followerId(), identity))
					.emailForce(false)
					.occurredAt(Instant.now())
					.build());
		} catch (RuntimeException exception) {
			log.warn("Follow notification skipped after commit. followerId={}, followingId={}, exceptionType={}",
					event.followerId(), event.followingId(), exception.getClass().getSimpleName());
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onBandFollowNotificationRequested(BandFollowNotificationRequestedEvent event) {
		if (event == null || event.followerId() == null || event.bandId() == null
				|| event.recipientIds().isEmpty()) return;
		try {
			FollowerNotificationIdentity identity = resolveFollowerIdentity(event.followerId());
			Band band = bandEntityFinder.getBand(event.bandId());
			String bandName = safe(band.getName(), UNKNOWN_BAND);
			Map<String, Object> payload = followerPayload("NEW_BAND_FOLLOWER", event.followerId(), identity);
			payload.put("bandId", event.bandId().toString());
			payload.put("bandName", bandName);
			Map<String, Object> immutablePayload = Map.copyOf(payload);
			Instant occurredAt = Instant.now();
			for (UUID recipientId : event.recipientIds()) {
				notificationProducer.publish(NotificationInboundEvent.builder()
						.recipientId(recipientId)
						.type(NotificationType.SOCIAL_NEW_BAND_FOLLOWER)
						.title(identity.username() + " bandını takip etmeye başladı")
						.message(bandName + " yeni bir takipçi kazandı.")
						.payload(immutablePayload)
						.emailForce(false)
						.occurredAt(occurredAt)
						.build());
			}
		} catch (RuntimeException exception) {
			log.warn("Band follow notification skipped after commit. followerId={}, bandId={}, exceptionType={}",
					event.followerId(), event.bandId(), exception.getClass().getSimpleName());
		}
	}

	private FollowerNotificationIdentity resolveFollowerIdentity(UUID followerId) {
		Map<UUID, GhostListenerIdentity> ghostIdentities = Objects.requireNonNull(
				ghostIdentityBatchResolver.resolve(List.of(followerId)),
				"Ghost identity resolver returned null"
		);
		GhostListenerIdentity ghostIdentity = ghostIdentities.get(followerId);
		if (ghostIdentity != null) {
			if (ghostIdentity.visibilityMode() != ListenerVisibilityMode.GHOST) {
				throw new IllegalStateException("Ghost identity resolver returned a non-ghost marker");
			}
			String username = safe(ghostIdentity.username(), UNKNOWN_USER);
			return new FollowerNotificationIdentity(
					username,
					username,
					normalize(ghostIdentity.profilePictureUrl()),
					ListenerVisibilityMode.GHOST
			);
		}

		User follower = userEntityFinder.getUser(followerId);
		var response = Objects.requireNonNull(
				publicProfileResolverService.resolveByUserId(followerId),
				"Public profile resolver returned null"
		);
		List<UserProfileTargetDto> profiles = response.profiles() == null
				? List.of()
				: response.profiles().stream().filter(Objects::nonNull).toList();
		String username = safe(follower.getUsername(), UNKNOWN_USER);
		String notificationName = profiles.stream()
				.filter(profile -> "VENUE".equalsIgnoreCase(profile.type()))
				.map(UserProfileTargetDto::displayName)
				.filter(this::hasText)
				.map(String::trim)
				.findFirst()
				.orElse(username);
		String avatarUrl = profiles.stream()
				.map(UserProfileTargetDto::profilePictureUrl)
				.filter(this::hasText)
				.map(String::trim)
				.findFirst()
				.orElseGet(() -> normalize(follower.getProfilePicture()));
		return new FollowerNotificationIdentity(username, notificationName, avatarUrl, null);
	}

	private Map<String, Object> followerPayload(
			String action,
			UUID followerId,
			FollowerNotificationIdentity identity
	) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("module", "SOCIAL");
		payload.put("action", action);
		payload.put("followerId", followerId.toString());
		payload.put("followerUsername", identity.username());
		if (hasText(identity.avatarUrl())) {
			payload.put("followerAvatarUrl", identity.avatarUrl());
		}
		if (identity.visibilityMode() == ListenerVisibilityMode.GHOST) {
			payload.put("followerVisibilityMode", ListenerVisibilityMode.GHOST.name());
		}
		return payload;
	}

	private String safe(String value, String fallback) {
		return hasText(value) ? value.trim() : fallback;
	}

	private String normalize(String value) {
		return hasText(value) ? value.trim() : null;
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private record FollowerNotificationIdentity(
			String username,
			String notificationName,
			String avatarUrl,
			ListenerVisibilityMode visibilityMode
	) {
	}
}
