package com.berkayb.soundconnect.modules.follow.event;

import java.util.UUID;

/**
 * Commit-bound request for a new-follower notification.
 *
 * <p>Only stable identifiers cross the transaction boundary. Identity and
 * avatar data are deliberately resolved after commit under the listener
 * visibility read lock.</p>
 */
public record FollowNotificationRequestedEvent(
		UUID followerId,
		UUID followingId
) {
}
