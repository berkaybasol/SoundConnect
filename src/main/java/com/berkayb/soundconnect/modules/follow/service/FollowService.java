package com.berkayb.soundconnect.modules.follow.service;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.follow.entity.Follow;

import java.util.List;


public interface FollowService {
	
	// bir kullanici baska bir kullaniciyi takip eder
	void follow(User follower, User following);
	
	// bir kullanici baska bir kullaniciyi takipten cikarir
	void unfollow(User follower, User following);
	
	
	// kullanicinin takip ettiklerini getirir.
	List<Follow> getFollowing(User follower);
	
	// kullanicinin takipcilerini gtetirir.
	List<Follow> getFollowers(User following);
	
	// iki kullanici arasinda takip iliskisi var mi ?
	boolean isFollowing(User follower, User following);
	
	// kullanicinin takip ettigi toplam kisi sayisi
	long countFollowing(User follower);
	
	// kullanicinin toplam takipci sayisi
	long countFollowers(User following);
	
	// TODO: Notification ve comment modülleri için uygun noktalara entegre edilecek.
}