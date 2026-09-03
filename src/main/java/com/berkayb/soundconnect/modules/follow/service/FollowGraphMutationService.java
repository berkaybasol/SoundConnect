package com.berkayb.soundconnect.modules.follow.service;

import java.util.UUID;

/**
 * Transactional write port for lifecycle operations that own a user's social
 * graph. It deliberately contains no listener-profile dependency so visibility
 * transitions can invoke it without introducing a service cycle.
 */
public interface FollowGraphMutationService {

	/**
	 * Permanently removes every follower of {@code userId}.
	 *
	 * <p>The caller must already hold the target listener profile's pessimistic
	 * visibility lock and an active transaction. This contract makes switching
	 * to ghost mode and purging incoming relationships one atomic operation.</p>
	 *
	 * @return the number of removed follow relationships
	 */
	int removeAllIncomingFollowers(UUID userId);
}
