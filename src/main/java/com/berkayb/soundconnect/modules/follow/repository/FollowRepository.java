package com.berkayb.soundconnect.modules.follow.repository;

import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FollowRepository extends JpaRepository<Follow, UUID> {
	
	// bir kullanici baska bir kullaniciyi takip ediyor mu?
	boolean existsByFollowerAndFollowing(User follower, User following);
	
	// bir kullanicinin takip ettigi kisilerin listesini getir.
	List<Follow> findAllByFollower(User follower);
	
	// bir kullanicinin takipcilerini getir.
	List<Follow> findAllByFollowing(User following);
	
	// takip kaydini follower ve followinge gore getir
	Optional<Follow> findByFollowerAndFollowing(User follower, User following);
	
	// bir kullanici kac kisiyi takip ediyor onu getir
	Long countByFollower(User follower);
	
	// bir kullanicinin kac takipcisi var onu getir
	Long countByFollowing(User following);
}