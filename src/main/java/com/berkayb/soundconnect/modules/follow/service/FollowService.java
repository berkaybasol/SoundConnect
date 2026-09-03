package com.berkayb.soundconnect.modules.follow.service;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.follow.entity.Follow;

import java.util.List;
import java.util.UUID;


public interface FollowService {
	
	// bir kullanici baska bir kullaniciyi takip eder
	void follow(User follower, User following);
	
	// bir kullanici baska bir kullaniciyi takipten cikarir
	void unfollow(User follower, User following);
	
	
	// kullanicinin takip ettiklerini getirir.
	List<Follow> getFollowing(User follower);

	// Hayalet profillerin sosyal grafiğini yalnızca profil sahibine açar.
	List<Follow> getFollowingVisibleTo(UUID viewerId, User follower);
	
	// kullanicinin takipcilerini gtetirir.
	List<Follow> getFollowers(User following);

	// Hayalet profillerin sosyal grafiğini yalnızca profil sahibine açar.
	List<Follow> getFollowersVisibleTo(UUID viewerId, User following);
	
	// iki kullanici arasinda takip iliskisi var mi ?
	boolean isFollowing(User follower, User following);

	// HTTP sorgusundaki follower kimliğinin oturum sahibine ait olduğunu doğrular.
	boolean isFollowingVisibleTo(User viewer, UUID requestedFollowerId, User following);
	
	// kullanicinin takip ettigi toplam kisi sayisi
	long countFollowing(User follower);

	long countFollowingVisibleTo(UUID viewerId, User follower);
	
	// kullanicinin toplam takipci sayisi
	long countFollowers(User following);

	long countFollowersVisibleTo(UUID viewerId, User following);
}
