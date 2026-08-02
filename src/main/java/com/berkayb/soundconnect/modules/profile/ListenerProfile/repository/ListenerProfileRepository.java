package com.berkayb.soundconnect.modules.profile.ListenerProfile.repository;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListenerProfileRepository extends JpaRepository<ListenerProfile, UUID> {
	Optional<ListenerProfile> findByUserId(UUID userId);
	
	@Query("""
        select lp
        from ListenerProfile lp
        where
            (:q is null or trim(:q) = '')
            or (:usernameQuery <> ''
                and locate(:usernameQuery, coalesce(lp.user.username, '')) > 0)
            or lower(coalesce(lp.description, '')) like lower(concat('%', :q, '%'))
    """)
	List<ListenerProfile> searchByUsernameOrBio(
			@Param("q") String q,
			@Param("usernameQuery") String usernameQuery
	);
}
