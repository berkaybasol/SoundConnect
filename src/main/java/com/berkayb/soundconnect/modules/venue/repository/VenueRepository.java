package com.berkayb.soundconnect.modules.venue.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VenueRepository extends JpaRepository<Venue, UUID> {


List<Venue> findAllByOwnerId(UUID ownerId);
Optional<Venue> findByIdAndOwnerId(UUID venueId, UUID ownerId);
boolean existsByOwner_Id(UUID ownerId);
	@EntityGraph(attributePaths = {"owner", "city", "district", "neighborhood"})
	@Query(
			value = """
					select v from Venue v
					where locate(lower(:q), lower(coalesce(v.name, ''))) > 0
					order by lower(v.name), v.id
					""",
			countQuery = """
					select count(v) from Venue v
					where locate(lower(:q), lower(coalesce(v.name, ''))) > 0
					"""
	)
	Page<Venue> searchByName(@Param("q") String q, Pageable pageable);
	
	
	Page<Venue> findByNameContainingIgnoreCase(String name, Pageable pageable);

	@EntityGraph(attributePaths = {"owner", "city", "district"})
	@Query("""
			select v
			from Venue v
			where locate(lower(:q), lower(coalesce(v.name, ''))) > 0
			   or (:usernameQuery <> ''
			       and locate(:usernameQuery, coalesce(v.owner.username, '')) > 0)
			order by
				case
					when lower(coalesce(v.name, v.owner.username, '')) = lower(:q) then 0
					when locate(lower(:q), lower(coalesce(v.name, v.owner.username, ''))) = 1 then 1
					else 2
				end,
				lower(coalesce(v.name, v.owner.username, '')),
				v.id
			""")
	Page<Venue> searchByNameOrOwnerUsername(
			@Param("q") String q,
			@Param("usernameQuery") String usernameQuery,
			Pageable pageable
	);
}
