package com.berkayb.soundconnect.modules.profile.StudioProfile.repository;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudioProfileRepository extends JpaRepository<StudioProfile, UUID> {
	// belirli bir kullaniciya ait studio profilini id ile getir
	Optional<StudioProfile> findByUserId(UUID userID);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select sp from StudioProfile sp where sp.user.id = :userId")
	Optional<StudioProfile> findByUserIdForUpdate(@Param("userId") UUID userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select sp from StudioProfile sp where sp.id = :profileId")
	Optional<StudioProfile> findByIdForUpdate(@Param("profileId") UUID profileId);

	Optional<StudioProfile> findById(UUID profileId);

	// belirli bir kullaniciya ait studio profilini studioname ile getir.
	Optional<StudioProfile> findStudioProfileByName(String name);

	@Query("select count(room) from StudioRoom room " +
			"where room.studioProfile.id = :profileId and room.archivedAt is null")
	long countActiveRooms(@Param("profileId") UUID profileId);

	@Query("select coalesce(sum(equipment.totalQuantity), 0) from StudioEquipment equipment " +
			"where equipment.studioProfile.id = :profileId and equipment.archivedAt is null")
	long sumActiveBacklineUnits(@Param("profileId") UUID profileId);

	@EntityGraph(attributePaths = {"user", "city", "district", "neighborhood"})
	@Query("""
        select sp
        from StudioProfile sp
        where
            (:q is null or trim(:q) = '')
            or lower(coalesce(sp.name, '')) like lower(concat('%', :q, '%'))
            or (:usernameQuery <> ''
                and locate(:usernameQuery, coalesce(sp.user.username, '')) > 0)
            or lower(coalesce(sp.description, '')) like lower(concat('%', :q, '%'))
    """)
	List<StudioProfile> searchByNameUsernameOrDescription(
			@Param("q") String q,
			@Param("usernameQuery") String usernameQuery
	);
}
