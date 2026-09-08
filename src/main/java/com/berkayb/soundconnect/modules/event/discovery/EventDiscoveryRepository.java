package com.berkayb.soundconnect.modules.event.discovery;

import com.berkayb.soundconnect.modules.event.entity.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.UUID;

/** Only to-one scalar joins participate in paging, so SQL limits never become in-memory limits. */
public interface EventDiscoveryRepository extends Repository<Event, UUID> {
    String FROM = """
            from Event event join event.venue venue join venue.owner owner
            join venue.city city join venue.district district join venue.neighborhood neighborhood
            """;
    String FILTER = """
            where event.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE
            and event.venueCalendarApproved = true
            and venue.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
            and owner.status = com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE
            and owner.emailVerified = true
            and event.eventDate = :date and city.id = :cityId
            and district.city.id = city.id and neighborhood.district.id = district.id
            and (:districtId is null or district.id = :districtId)
            and (:neighborhoodId is null or neighborhood.id = :neighborhoodId)
            """;

    @Query(value = """
            select new com.berkayb.soundconnect.modules.event.discovery.EventDiscoveryRow(
                event.id, event.title, event.posterImage,
                musician.id, musicianUser.username, musician.stageName,
                band.id, band.name, event.manualPerformerName,
                venue.id, venue.name, city.name, district.name, neighborhood.name,
                event.eventDate, event.startTime, event.endTime, event.description)
            """ + FROM + """
            left join event.musicianProfile musician
                on event.performerApprovalStatus = com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus.APPROVED
            left join musician.user musicianUser
            left join event.band band
                on event.performerApprovalStatus = com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus.APPROVED
            """ + FILTER + " order by event.startTime, event.id",
            countQuery = "select count(event) " + FROM + FILTER)
    Page<EventDiscoveryRow> findEvents(@Param("date") LocalDate date, @Param("cityId") UUID cityId,
            @Param("districtId") UUID districtId, @Param("neighborhoodId") UUID neighborhoodId,
            Pageable pageable);
}
