package com.berkayb.soundconnect.modules.feed.musician.preference.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Bounded owner/profile reads used only to build the feed bootstrap contract. */
public interface MusicianFeedProfileReadRepository extends Repository<MusicianProfile, UUID> {

	/*
	 * Scalar native locks avoid returning a managed profile that may have been
	 * loaded before the lock. All musician-profile writers already lock this row,
	 * so profile fields, instrument links and feed preferences share one fence.
	 */
	@Query(value = "select id from tbl_musician_profile where user_id = :userId for share", nativeQuery = true)
	Optional<UUID> lockOwnedProfileForRead(@Param("userId") UUID userId);

	@Query(value = "select id from tbl_musician_profile where user_id = :userId for update", nativeQuery = true)
	Optional<UUID> lockOwnedProfileForUpdate(@Param("userId") UUID userId);

	@EntityGraph(attributePaths = "instruments")
	@Query("select profile from MusicianProfile profile where profile.id = :profileId")
	Optional<MusicianProfile> findByIdWithInstruments(@Param("profileId") UUID profileId);

	/**
	 * Portfolio completion counts only currently public, READY native media.
	 * Musician Spotify snapshots are client supplied today and therefore are not
	 * authoritative completion evidence until a verified Spotify write path exists.
	 */
	@Query(value = """
			select (
				exists (
					select 1 from tbl_tracks track
					join tbl_media_asset asset on asset.id = track.media_asset_id
					where track.owner_type = 'MUSICIAN_PROFILE'
					  and track.owner_id = :profileId
					  and asset.owner_type = 'MUSICIAN_PROFILE'
					  and asset.owner_id = :profileId
					  and asset.status = 'READY' and asset.visibility = 'PUBLIC'
				)
				or exists (
					select 1 from tbl_profile_media media
					join tbl_media_asset asset on asset.id = media.media_asset_id
					where media.profile_type = 'MUSICIAN'
					  and media.profile_id = :profileId
					  and asset.owner_type = 'MUSICIAN_PROFILE'
					  and asset.owner_id = :profileId
					  and asset.status = 'READY' and asset.visibility = 'PUBLIC'
				)
			)
			""", nativeQuery = true)
	boolean hasPublicPortfolio(@Param("profileId") UUID profileId);
}
