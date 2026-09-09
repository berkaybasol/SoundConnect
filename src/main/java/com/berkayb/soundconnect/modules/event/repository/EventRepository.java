package com.berkayb.soundconnect.modules.event.repository;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

	/** All public entry points enforce the same venue/account/location eligibility as discovery. */
	String PUBLIC_EVENT = """
			select e from Event e join e.venue venue join venue.owner owner
			join venue.city city join venue.district district join venue.neighborhood neighborhood
			where e.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE
			and e.venueCalendarApproved = true
			and venue.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
			and owner.status = com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE
			and owner.emailVerified = true
			and district.city.id = city.id and neighborhood.district.id = district.id
			and e.eventDate is not null and e.startTime is not null
			""";
	String PUBLIC_ORDER = " order by e.eventDate, e.startTime, e.id";

	@EntityGraph("event.card")
	@Query(PUBLIC_EVENT + " and e.id = :eventId")
	Optional<Event> findPublicById(@Param("eventId") UUID eventId);

	boolean existsByIdAndEventOrigin(UUID eventId, EventOrigin eventOrigin);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select event from Event event where event.id = :eventId")
	java.util.Optional<Event> findByIdForUpdate(@Param("eventId") UUID eventId);
	
	// bir mekandaki tum etkinlikler
	@EntityGraph("event.card")
	@Query(PUBLIC_EVENT + " and venue = :venue" + PUBLIC_ORDER)
	List<Event> findByVenue(@Param("venue") Venue venue);
	
	// belirli tarihteki etkinlikler
	@EntityGraph("event.card")
	@Query(PUBLIC_EVENT + " and e.eventDate = :eventDate" + PUBLIC_ORDER)
	List<Event> findByEventDate(@Param("eventDate") LocalDate eventDate);
	
	// secilen sehre gore
	@EntityGraph("event.card")
	@Query(PUBLIC_EVENT + " and city.id = :cityId" + PUBLIC_ORDER)
	List<Event> findByVenue_City_Id(@Param("cityId") UUID cityId);
	
	// secilen ilceye gore
	@EntityGraph("event.card")
	@Query(PUBLIC_EVENT + " and district.id = :districtId" + PUBLIC_ORDER)
	List<Event> findByVenue_District_Id(@Param("districtId") UUID districtId);
	
	// secilen mahalleye gore
	@EntityGraph("event.card")
	@Query(PUBLIC_EVENT + " and neighborhood.id = :neighborhoodId" + PUBLIC_ORDER)
	List<Event> findByVenue_Neighborhood_Id(@Param("neighborhoodId") UUID neighborhoodId);
	
	@EntityGraph("event.card")
	@Query("select e from Event e where e.venue = :venue and e.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE order by e.eventDate, e.startTime, e.id")
	List<Event> findByVenueOrderByEventDateAscStartTimeAsc(@Param("venue") Venue venue);
	
	@EntityGraph("event.card")
	@Query("select e from Event e where e.venue = :venue and e.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE and e.eventDate between :startDate and :endDate order by e.eventDate, e.startTime, e.id")
	List<Event> findByVenueAndEventDateBetweenOrderByEventDateAscStartTimeAsc(
			@Param("venue") Venue venue, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate
	);

	@EntityGraph("event.card")
	@Query(PUBLIC_EVENT + " and venue = :venue and e.eventDate between :startDate and :endDate" + PUBLIC_ORDER)
	List<Event> findPublicByVenueBetween(@Param("venue") Venue venue,
			@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select event
			from Event event
			where event.band.id = :bandId
			order by event.id
			""")
	List<Event> findAllByBandIdForUpdate(@Param("bandId") UUID bandId);
}
