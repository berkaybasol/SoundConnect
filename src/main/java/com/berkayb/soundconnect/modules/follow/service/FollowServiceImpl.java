package com.berkayb.soundconnect.modules.follow.service;

import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.follow.event.FollowNotificationRequestedEvent;
import com.berkayb.soundconnect.modules.follow.repository.FollowRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;



@Service
@RequiredArgsConstructor
@Slf4j
public class FollowServiceImpl implements FollowService {
	private final FollowRepository followRepository;
	private final ListenerVisibilityPolicy listenerVisibilityPolicy;
	private final ListenerProfileChoiceStatusReader listenerProfileChoiceStatusReader;
	private final ApplicationEventPublisher applicationEventPublisher;
	
	@Transactional // islemlerden birinde bile hata olursa butun islemleri geri al
	@Override
	public void follow(User follower, User following) {
		log.info("User {} is trying to follow user {}", follower.getId(), following.getId());
		
		// kullanici kendini takip edemez.
		if (follower.getId().equals(following.getId())) {
			log.warn("User {} tried to follow themselves :D", follower.getId());
			throw new SoundConnectException(ErrorType.CANNOT_FOLLOW_SELF);
		}

		// Follow creation and ghost activation serialize on the same listener row.
		// Therefore activation either purges this relationship after it commits, or
		// this request observes GHOST and cannot insert it after the purge.
		if (listenerProfileChoiceStatusReader.requiresChoice(following)) {
			// A listener who has not completed onboarding is not a public target.
			// Keep this indistinguishable from any other unresolved public profile.
			log.info("Follow rejected because target user {} has no public listener profile", following.getId());
			throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		}
		if (listenerVisibilityPolicy.lockAndIsPubliclyRestricted(following.getId())) {
			log.info("Follow rejected because target user {} has a restricted listener profile", following.getId());
			throw new SoundConnectException(ErrorType.GHOST_PROFILE_CANNOT_BE_FOLLOWED);
		}
		
		// zaten takip ediyorsa
		if (followRepository.existsByFollowerAndFollowing(follower, following)) {
			log.warn("User {} already follows user {}", follower.getId(), following.getId());
			throw new SoundConnectException(ErrorType.ALREADY_FOLLOWING);
		}
		
		// takip islemini gerceklestir
		Follow follow = Follow.builder()
				.follower(follower)
				.following(following)
				.followedAt(LocalDateTime.now())
				.build();
		
		followRepository.save(follow);
		
		log.info("User {} succesfully followed user {}", follower.getId(), following.getId());
		
		requestNewFollowerNotification(follower.getId(), following.getId());
	}
	
	@Transactional // islemlerden biri bile basarisiz olursa butun islemleri geri al
	@Override
	public void unfollow(User follower, User following) {
	log.info("User {} is trying to unfollow user {}", follower.getId(), following.getId());
	
	// kullanici takip etmedigi birini unfollow etmeye calisirsa hata firlat.
	Follow follow = followRepository.findByFollowerAndFollowing(follower, following)
			.orElseThrow(() -> {
				log.warn("User {} tried to unfollow user {} but they do not follow each other", follower.getId(), following.getId());
				return new SoundConnectException(ErrorType.FOLLOW_RELATION_NOT_FOUND);
			});
	
	// takipten cikma islemini gerceklestir
	followRepository.delete(follow);
	
	log.info("User {} succesfully unfollowed user {}", follower.getId(), following.getId());
	
	//TODO notification modulu geldiginde burada bildirim tetiklencek
	}
	
	@Transactional(readOnly = true)
	@Override
	public List<Follow> getFollowing(User follower) {
		return followRepository.findAllByFollower(follower);
	}

	@Transactional
	@Override
	public List<Follow> getFollowingVisibleTo(UUID viewerId, User follower) {
		assertCanViewGraph(viewerId, follower);
		return getFollowing(follower);
	}
	
	@Transactional(readOnly = true)
	@Override
	public List<Follow> getFollowers(User following) {
		return followRepository.findAllByFollowing(following);
	}

	@Transactional
	@Override
	public List<Follow> getFollowersVisibleTo(UUID viewerId, User following) {
		assertCanViewGraph(viewerId, following);
		return getFollowers(following);
	}
	
	@Transactional(readOnly = true)
	@Override
	public boolean isFollowing(User follower, User following) {
		return followRepository.existsByFollowerAndFollowing(follower, following);
	}

	@Transactional
	@Override
	public boolean isFollowingVisibleTo(User viewer, UUID requestedFollowerId, User following) {
		if (requestedFollowerId != null && !viewer.getId().equals(requestedFollowerId)) {
			throw new SoundConnectException(ErrorType.FOLLOW_RELATION_QUERY_FORBIDDEN);
		}
		// A committed ghost transition owns the linearization point on this row.
		// Once observed, the incoming edge is private and should already have been
		// purged; returning false also fails closed while reconciliation runs.
		if (listenerProfileChoiceStatusReader.requiresChoice(following)
				|| listenerVisibilityPolicy.lockForReadAndIsPubliclyRestricted(following.getId())) {
			return false;
		}
		return isFollowing(viewer, following);
	}
	
	@Transactional(readOnly = true)
	@Override
	public long countFollowing(User follower) {
		return followRepository.countByFollower(follower);
	}

	@Transactional
	@Override
	public long countFollowingVisibleTo(UUID viewerId, User follower) {
		assertCanViewGraph(viewerId, follower);
		return countFollowing(follower);
	}
	
	@Transactional(readOnly = true)
	@Override
	public long countFollowers(User following) {
		return followRepository.countByFollowing(following);
	}

	@Transactional
	@Override
	public long countFollowersVisibleTo(UUID viewerId, User following) {
		assertCanViewGraph(viewerId, following);
		return countFollowers(following);
	}

	private void assertCanViewGraph(UUID viewerId, User subject) {
		UUID subjectUserId = subject.getId();
		if (listenerProfileChoiceStatusReader.requiresChoice(subject)) {
			throw new SoundConnectException(ErrorType.FOLLOW_GRAPH_PRIVATE);
		}
		boolean restricted = listenerVisibilityPolicy
				.lockForReadAndIsPubliclyRestricted(subjectUserId);
		if (!subjectUserId.equals(viewerId) && restricted) {
			throw new SoundConnectException(ErrorType.FOLLOW_GRAPH_PRIVATE);
		}
	}

	private void requestNewFollowerNotification(UUID followerId, UUID followingId) {
		try {
			applicationEventPublisher.publishEvent(
					new FollowNotificationRequestedEvent(followerId, followingId)
			);
		} catch (RuntimeException exception) {
			// Notification registration is best effort and must never roll back the
			// successfully validated follow relationship.
			log.warn("Follow notification request failed. followerId={}, followingId={}, exceptionType={}",
					followerId, followingId, exception.getClass().getSimpleName());
		}
	}
}
