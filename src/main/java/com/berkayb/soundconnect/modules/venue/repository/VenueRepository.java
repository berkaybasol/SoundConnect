package com.berkayb.soundconnect.modules.venue.repository;

import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VenueRepository extends JpaRepository<Venue, UUID> {

}