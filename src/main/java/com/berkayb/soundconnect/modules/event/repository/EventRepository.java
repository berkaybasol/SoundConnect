package com.berkayb.soundconnect.modules.event.repository;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

	boolean existsByIdAndEventOrigin(UUID eventId, EventOrigin eventOrigin);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select event from Event event where event.id = :eventId")
	java.util.Optional<Event> findByIdForUpdate(@Param("eventId") UUID eventId);
	
	// bir mekandaki tum etkinlikler
	@Query("select e from Event e where e.venue = :venue and e.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE")
	List<Event> findByVenue(Venue venue);
	
	// belirli tarihteki etkinlikler
	@Query("select e from Event e where e.eventDate = :eventDate and e.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE")
	List<Event> findByEventDate(@Param("eventDate") LocalDate eventDate);
	
	// secilen sehre gore
	@Query("select e from Event e where e.venue.city.id = :cityId and e.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE")
	List<Event> findByVenue_City_Id(@Param("cityId") UUID cityId);
	
	// secilen ilceye gore
	@Query("select e from Event e where e.venue.district.id = :districtId and e.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE")
	List<Event> findByVenue_District_Id(@Param("districtId") UUID districtId);
	
	// secilen mahalleye gore
	@Query("select e from Event e where e.venue.neighborhood.id = :neighborhoodId and e.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE")
	List<Event> findByVenue_Neighborhood_Id(@Param("neighborhoodId") UUID neighborhoodId);
	
	@Query("select e from Event e where e.venue = :venue and e.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE order by e.eventDate, e.startTime, e.id")
	List<Event> findByVenueOrderByEventDateAscStartTimeAsc(@Param("venue") Venue venue);
	
	@Query("select e from Event e where e.venue = :venue and e.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE and e.eventDate between :startDate and :endDate order by e.eventDate, e.startTime, e.id")
	List<Event> findByVenueAndEventDateBetweenOrderByEventDateAscStartTimeAsc(
			@Param("venue") Venue venue, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select event
			from Event event
			where event.band.id = :bandId
			order by event.id
			""")
	List<Event> findAllByBandIdForUpdate(@Param("bandId") UUID bandId);
}
