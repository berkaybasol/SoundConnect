package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.entity.MusicianCalendarSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;

public interface MusicianCalendarSettingsRepository extends JpaRepository<MusicianCalendarSettings, UUID> {
	@Query(value = "select id from tbl_musician_profile where user_id = :userId", nativeQuery = true)
	Optional<UUID> findOwnedProfileId(@Param("userId") UUID userId);

	/**
	 * Lock band parents before the musician parent, matching band aggregate writes.
	 * Do not filter on settings here: visibility is authoritative only after these
	 * locks have been acquired. The returned IDs also fence both later event queries
	 * against membership/event insertions that occur after this statement's snapshot.
	 */
	@Query(value = """
			select band.id from tbl_band band
			where exists (
			    select 1 from tbl_band_member member
			    where member.band_id = band.id and member.status = 'ACTIVE'
			      and member.user_id = (select profile.user_id from tbl_musician_profile profile where profile.id = :profileId)
			) and exists (
			    select 1 from tbl_event event where event.band_id = band.id
			      and event.performer_approval_status = 'APPROVED'
			      and event.event_date between :startDate and :endDate
			)
			order by band.id limit :limit for share of band
			""", nativeQuery = true)
	List<UUID> lockCalendarBandsForRead(@Param("profileId") UUID profileId,
			@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
			@Param("limit") int limit);

	// Scalar native locks avoid eagerly loading a stale profile graph, outer-join
	// locking, and any need to create settings rows during public GET requests.
	@Query(value = "select id from tbl_musician_profile where id = :profileId for share", nativeQuery = true)
	Optional<UUID> lockProfileForRead(@Param("profileId") UUID profileId);

	@Query(value = "select id from tbl_musician_profile where user_id = :userId for share", nativeQuery = true)
	Optional<UUID> lockOwnedProfileForRead(@Param("userId") UUID userId);

	@Query(value = "select id from tbl_musician_profile where user_id = :userId for update", nativeQuery = true)
	Optional<UUID> lockOwnedProfileForUpdate(@Param("userId") UUID userId);
}
