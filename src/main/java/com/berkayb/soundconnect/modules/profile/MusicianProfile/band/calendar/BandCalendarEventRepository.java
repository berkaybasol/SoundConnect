package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar;

import com.berkayb.soundconnect.modules.event.entity.Event;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BandCalendarEventRepository extends Repository<Event, UUID> {
	@Query("""
			select event.id from Event event where event.band.id = :bandId
			and event.eventDate between :startDate and :endDate
			and event.performerApprovalStatus = com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus.APPROVED
			and event.profileCalendarApproved = true
			and event.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE
			and event.venue.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
			order by event.eventDate, event.startTime, event.id
			""")
	Slice<UUID> findApprovedEventIds(@Param("bandId") UUID bandId, @Param("startDate") LocalDate startDate,
			@Param("endDate") LocalDate endDate, Pageable pageable);

	@EntityGraph(attributePaths = {"venue.city", "venue.district", "venue.neighborhood", "musicianProfile.user",
			"band.members.user.musicianProfile.user"})
	@Query("""
			select event from Event event where event.id in :ids and event.band.id = :bandId
			and event.performerApprovalStatus = com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus.APPROVED
			and event.profileCalendarApproved = true
			and event.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE
			and event.venue.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
			""")
	List<Event> findCalendarDetails(@Param("ids") Collection<UUID> ids, @Param("bandId") UUID bandId);
}
