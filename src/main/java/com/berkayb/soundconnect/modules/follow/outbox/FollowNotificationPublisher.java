package com.berkayb.soundconnect.modules.follow.outbox;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import org.springframework.jdbc.core.JdbcTemplate;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

/** Holds account and current visibility read locks through confirmed publication on one worker connection.
 * A resolver/broker exception leaves durable retry work; never fall back after a resolver failure. */
@Component
@RequiredArgsConstructor
@Slf4j
public class FollowNotificationPublisher {
	private static final String UNKNOWN_USER = "Bir kullanıcı";
	private static final String UNKNOWN_BAND = "Band";

	private final NotificationProducer notificationProducer;
	private final GhostListenerIdentityBatchResolver ghostIdentityBatchResolver;
	private final UserEntityFinder userEntityFinder;
	private final PublicProfileResolverService publicProfileResolverService;
	private final BandEntityFinder bandEntityFinder;

    private final AccountDeliveryFence accounts;
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publish(FollowNotificationOutboxClaim claim) {
        if (!accounts.canDeliver(claim.recipientId(), Set.of(claim.followerId()))) return false;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tbl_user where id = ?)",
                Boolean.class, claim.followerId()))) return false;
        // A deleted band is terminal. Unfollow is intentionally not checked: this is a historical occurrence.
        if (claim.bandId() != null && jdbc.queryForList(
                "select id from tbl_band where id = ? for share", UUID.class, claim.bandId()).isEmpty()) return false;
        FollowerNotificationIdentity identity = resolveFollowerIdentity(claim.followerId());
        String title = identity.notificationName() + " seni takip etmeye başladı";
        String message = "Yeni bir takipçin var.";
        Map<String, Object> payload = followerPayload("NEW_FOLLOWER", claim.followerId(), identity);
        if (claim.bandId() != null) {
            Band band = bandEntityFinder.getBand(claim.bandId());
            String bandName = safe(band.getName(), UNKNOWN_BAND);
            payload.put("action", "NEW_BAND_FOLLOWER");
            payload.put("bandId", claim.bandId().toString());
            payload.put("bandName", bandName);
            title = identity.username() + " bandını takip etmeye başladı";
            message = bandName + " yeni bir takipçi kazandı.";
        }
        notificationProducer.publishConfirmed(NotificationInboundEvent.builder()
                .eventId(claim.eventId()).recipientId(claim.recipientId()).type(claim.type())
                .title(title).message(message).payload(Map.copyOf(payload)).emailForce(false)
                .occurredAt(claim.occurredAt()).build());
        return true;
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
