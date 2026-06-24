package com.berkayb.soundconnect.modules.follow.band.repository;

import com.berkayb.soundconnect.modules.follow.band.entity.BandFollow;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BandFollowRepository extends JpaRepository<BandFollow, UUID> {
	
	boolean existsByFollowerAndBand(User follower, Band band);
	
	Optional<BandFollow> findByFollowerAndBand(User follower, Band band);
	
	List<BandFollow> findAllByFollower(User follower);
	
	List<BandFollow> findAllByBand(Band band);
	
	long countByBand(Band band);

	void deleteAllByBand(Band band);
}
