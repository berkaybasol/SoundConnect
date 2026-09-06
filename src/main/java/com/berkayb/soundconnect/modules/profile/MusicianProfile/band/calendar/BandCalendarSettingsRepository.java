package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BandCalendarSettingsRepository extends JpaRepository<BandCalendarSettings, UUID> {
	@Query(value = "select id from tbl_band where id = :bandId for share", nativeQuery = true)
	Optional<UUID> lockBandForRead(@Param("bandId") UUID bandId);

	@Query(value = "select id from tbl_band where id = :bandId for update", nativeQuery = true)
	Optional<UUID> lockBandForUpdate(@Param("bandId") UUID bandId);

	@Query(value = """
			select exists(select 1 from tbl_band_member where band_id = :bandId and user_id = :userId
			and status = 'ACTIVE' and band_role = 'FOUNDER')
			""", nativeQuery = true)
	boolean isActiveFounder(@Param("bandId") UUID bandId, @Param("userId") UUID userId);

	// Recheck authority inside the mutation transaction and hold membership until
	// commit. Concurrent founder removal cannot authorize a stale preference write.
	@Query(value = """
			select id from tbl_band_member where band_id = :bandId and user_id = :userId
			and status = 'ACTIVE' and band_role = 'FOUNDER' for share
			""", nativeQuery = true)
	Optional<UUID> lockActiveFounder(@Param("bandId") UUID bandId, @Param("userId") UUID userId);
}
