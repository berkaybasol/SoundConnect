package com.berkayb.soundconnect.modules.follow.service;

import com.berkayb.soundconnect.modules.follow.repository.FollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FollowGraphMutationServiceImpl implements FollowGraphMutationService {

	private final FollowRepository followRepository;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public int removeAllIncomingFollowers(UUID userId) {
		Objects.requireNonNull(userId, "userId must not be null");
		int removedCount = followRepository.deleteAllIncomingByUserId(userId);
		if (removedCount > 0) {
			log.info("Removed {} incoming follow relationship(s) during visibility transition for user {}",
					removedCount, userId);
		}
		return removedCount;
	}
}
