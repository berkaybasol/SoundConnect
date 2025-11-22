package com.berkayb.soundconnect.modules.social.follow.service;

import com.berkayb.soundconnect.modules.social.follow.entity.Follow;
import com.berkayb.soundconnect.modules.social.follow.repository.FollowRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;



@Service
@RequiredArgsConstructor
@Slf4j
public class FollowServiceImpl implements FollowService {
	private final FollowRepository followRepository;
	
	@Transactional // islemlerden birinde bile hata olursa butun islemleri geri al
	@Override
	public void follow(User follower, User following) {
		log.info("User {} is trying to follow user {}", follower.getId(), following.getId());
		
		// kullanici kendini takip edemez.
		if (follower.getId().equals(following.getId())) {
			log.warn("User {} tried to follow themselves :D", follower.getId());
			throw new SoundConnectException(ErrorType.CANNOT_FOLLOW_SELF);
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
		
		//TODO nitification modulu geldiginde burada bildirim tetiklencek.
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
	
	@Transactional(readOnly = true)
	@Override
	public List<Follow> getFollowers(User following) {
		return followRepository.findAllByFollowing(following);
	}
	
	@Transactional(readOnly = true)
	@Override
	public boolean isFollowing(User follower, User following) {
		return followRepository.existsByFollowerAndFollowing(follower, following);
	}
	
	@Transactional(readOnly = true)
	@Override
	public long countFollowing(User follower) {
		return followRepository.countByFollower(follower);
	}
	
	@Transactional(readOnly = true)
	@Override
	public long countFollowers(User following) {
		return followRepository.countByFollowing(following);
	}
}