package com.berkayb.soundconnect.modules.comment.publicevent;

import com.berkayb.soundconnect.modules.event.entity.Event;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/** Scalar eligibility checks. No generic comment target is made public by this adapter. */
public interface EventCommentReadRepository extends Repository<Event, UUID> {
    @Query("""
            select (count(event) > 0) from Event event
            join event.venue venue join venue.owner owner
            join venue.city city join venue.district district join venue.neighborhood neighborhood
            where event.id = :eventId
                and event.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE
                and event.venueCalendarApproved = true
                and venue.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
                and owner.status = com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE
                and owner.emailVerified = true
                and district.city.id = city.id and neighborhood.district.id = district.id
                and event.eventDate is not null and event.startTime is not null
            """)
    boolean existsPublicEvent(@Param("eventId") UUID eventId);

    @Query("""
            select (count(comment) > 0) from Comment comment
            where comment.id = :commentId
                and comment.targetType = com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType.EVENT
                and comment.targetId = :eventId and comment.parentComment is null
            """)
    boolean existsEventRootComment(@Param("eventId") UUID eventId, @Param("commentId") UUID commentId);
}
