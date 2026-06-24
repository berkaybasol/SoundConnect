package com.berkayb.soundconnect.modules.profile.StudioProfile.repository;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudioProfileRepository extends JpaRepository<StudioProfile, UUID> {
	// belirli bir kullaniciya ait studio profilini id ile getir
	Optional<StudioProfile> findByUserId(UUID userID);

	// belirli bir kullaniciya ait studio profilini studioname ile getir.
	Optional<StudioProfile> findStudioProfileByName(String name);

	@Query("""
        select sp
        from StudioProfile sp
        where
            (:q is null or trim(:q) = '')
            or lower(coalesce(sp.name, '')) like lower(concat('%', :q, '%'))
            or lower(coalesce(sp.user.username, '')) like lower(concat('%', :q, '%'))
            or lower(coalesce(sp.description, '')) like lower(concat('%', :q, '%'))
    """)
	List<StudioProfile> searchByNameUsernameOrDescription(@Param("q") String q);
}
