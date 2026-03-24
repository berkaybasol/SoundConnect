package com.berkayb.soundconnect.modules.venue.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
	@Query("""
   select v from Venue v
   where lower(v.name) like lower(concat('%', :q, '%'))
""")
	Page<Venue> searchByName(@Param("q") String q, Pageable pageable);
	
	
	Page<Venue> findByNameContainingIgnoreCase(String name, Pageable pageable);
}