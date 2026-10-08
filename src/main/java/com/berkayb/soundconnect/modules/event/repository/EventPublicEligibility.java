package com.berkayb.soundconnect.modules.event.repository;

/** Public calendar queries must apply the same event eligibility as discovery and detail reads. */
public final class EventPublicEligibility {
    private EventPublicEligibility() { }

    /** JPQL predicate for queries whose Event alias is {@code event}. */
    public static final String PREDICATE = """
            event.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE
            and event.venueCalendarApproved = true
            and event.venue.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
            and event.venue.owner.status = com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE
            and event.venue.owner.emailVerified = true
            and event.venue.district.city.id = event.venue.city.id
            and event.venue.neighborhood.district.id = event.venue.district.id
            and event.eventDate is not null and event.startTime is not null
            """;
}
