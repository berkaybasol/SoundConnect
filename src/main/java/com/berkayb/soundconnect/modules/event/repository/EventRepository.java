package com.berkayb.soundconnect.modules.event.repository;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {
	
	// bir mekandaki tum etkinlikler
	List<Event> findByVenue(Venue venue);
	
	// belirli tarihteki etkinlikler
	List<Event> findByEventDate(LocalDate eventDate);
	
	// secilen sehre gore
	List<Event> findByVenue_City_Id(UUID cityId);
	
	// secilen ilceye gore
	List<Event> findByVenue_District_Id(UUID districtId);
	
	// secilen mahalleye gore
	List<Event> findByVenue_Neighborhood_Id(UUID neighborhoodId);
}