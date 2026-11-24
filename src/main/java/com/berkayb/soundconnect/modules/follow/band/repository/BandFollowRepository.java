package com.berkayb.soundconnect.modules.follow.band.repository;

import com.berkayb.soundconnect.modules.follow.band.entity.BandFollow;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BandFollowRepository extends JpaRepository<BandFollow, UUID> {
	
	boolean existsByFollowerAndBand(User follower, Band band);
	
	Optional<BandFollow> findByFollowerAndBand(User follower, Band band);
	
	List<BandFollow> findAllByFollower(User follower);
	
	String VISIBLE_FOLLOWERS = """
			from tbl_band_follow f where f.band_id = :bandId
			and not exists (select 1 from tbl_user_block b where b.unblocked_at is null
			    and ((b.blocker_id = :viewerId and b.blocked_id = f.follower_id)
			      or (b.blocked_id = :viewerId and b.blocker_id = f.follower_id)))
			""";

	@Query(value = "select f.* " + VISIBLE_FOLLOWERS + " order by f.followed_at, f.id", nativeQuery = true)
	List<BandFollow> visibleFollowers(@Param("bandId") UUID bandId, @Param("viewerId") UUID viewerId);

	@Query(value = "select count(*) " + VISIBLE_FOLLOWERS, nativeQuery = true)
	long countVisibleFollowers(@Param("bandId") UUID bandId, @Param("viewerId") UUID viewerId);

	void deleteAllByBand(Band band);
}
