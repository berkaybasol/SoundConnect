package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.repository;

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

public interface MusicianCalendarEventRepository extends Repository<Event, UUID> {

	/**
	 * Slice IDs first: fetching band members in the paged query would paginate in memory.
	 * Only venue-origin events are published. Retained reciprocal rows are compatible
	 * with the database schema but excluded from this venue-only product flow.
	 */
	@Query("""
			select event.id from Event event
			left join event.venue venue
			left join event.musicianProfile musician
			where event.eventDate between :startDate and :endDate
			  and event.performerApprovalStatus = com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus.APPROVED
			  and event.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE
			  and venue.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
			  and ((event.musicianProfile.id = :profileId and event.profileCalendarApproved = true) or (event.band.id in :lockedBandIds and exists (
			      select publication.id.eventId from EventMemberPublication publication
			      where publication.id.eventId = event.id and publication.id.musicianProfileId = :profileId and publication.visible = true
			  ) and exists (
			      select member.id from BandMember member
			      where member.band.id = event.band.id
			        and member.status = com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus.ACTIVE
			        and member.user.id = (select profile.user.id from MusicianProfile profile where profile.id = :profileId)
			  )))
			order by event.eventDate, event.startTime, event.id
			""")
	Slice<UUID> findApprovedEventIds(
			@Param("profileId") UUID profileId,
			@Param("startDate") LocalDate startDate,
			@Param("endDate") LocalDate endDate,
			@Param("lockedBandIds") Collection<UUID> lockedBandIds,
			Pageable pageable
	);

	@EntityGraph(attributePaths = {
			"venue.city", "venue.district", "venue.neighborhood", "musicianProfile.user",
			"band.members.user.musicianProfile.user"
	})
	@Query("""
			select event from Event event
			left join event.venue venue
			left join event.musicianProfile musician
			where event.id in :ids
			  and event.performerApprovalStatus = com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus.APPROVED
			  and event.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE
			  and venue.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
			  and ((event.musicianProfile.id = :profileId and event.profileCalendarApproved = true) or (event.band.id in :lockedBandIds and exists (
			      select publication.id.eventId from EventMemberPublication publication
			      where publication.id.eventId = event.id and publication.id.musicianProfileId = :profileId and publication.visible = true
			  ) and exists (
			      select member.id from BandMember member
			      where member.band.id = event.band.id
			        and member.status = com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus.ACTIVE
			        and member.user.id = (select profile.user.id from MusicianProfile profile where profile.id = :profileId)
			  )))
			""")
	List<Event> findCalendarDetails(@Param("ids") Collection<UUID> ids, @Param("profileId") UUID profileId,
			@Param("lockedBandIds") Collection<UUID> lockedBandIds);
}
