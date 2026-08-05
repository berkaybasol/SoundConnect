package com.berkayb.soundconnect.modules.profile.ListenerProfile.repository;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListenerProfileRepository extends JpaRepository<ListenerProfile, UUID> {
	Optional<ListenerProfile> findByUserId(UUID userId);
	
	@EntityGraph(attributePaths = "user")
	@Query("""
        select lp
        from ListenerProfile lp
        where
            (:q is null or trim(:q) = '')
            or locate(lower(:q), lower(coalesce(lp.name, ''))) > 0
            or (:usernameQuery <> ''
                and locate(:usernameQuery, coalesce(lp.user.username, '')) > 0)
            or locate(lower(:q), lower(coalesce(lp.description, ''))) > 0
        order by
            case
                when lower(coalesce(lp.name, lp.user.username, '')) = lower(:q) then 0
                when locate(lower(:q), lower(coalesce(lp.name, lp.user.username, ''))) = 1 then 1
                else 2
            end,
            lower(coalesce(lp.name, lp.user.username, '')),
            lp.id
    """)
	List<ListenerProfile> searchByUsernameOrBio(
			@Param("q") String q,
			@Param("usernameQuery") String usernameQuery,
			Pageable pageable
	);
}
