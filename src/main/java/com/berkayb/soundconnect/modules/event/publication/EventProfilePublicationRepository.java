package com.berkayb.soundconnect.modules.event.publication;

import com.berkayb.soundconnect.modules.event.entity.Event;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.*;
import java.time.*;

public interface EventProfilePublicationRepository extends Repository<Event, UUID> {
    // Filter IDs and count BEFORE page limits. Hibernate's UTC JDBC calendar can
    // shift LocalTime's epoch-based storage on non-UTC JVMs. Binding midnight with
    // the same LocalTime mapping gives its exact storage origin (also 00:00 when
    // direct java.time JDBC mapping is enabled). Relative seconds recover the same
    // logical wall time that Hibernate returns, without rewriting existing rows.
    String SQL_BASE = """
        from tbl_event event join tbl_venues venue on venue.id = event.venue_id
        where event.event_origin = 'VENUE' and event.performer_approval_status = 'APPROVED'
        and venue.status = 'APPROVED'
        and not exists (select 1 from event_performer_requests request
            where request.event_id = event.id and request.status = 'PENDING')
        """;
    String SQL_MUSICIAN = """
        and (event.musician_profile_id = :targetId or (event.band_id is not null and exists (
            select 1 from tbl_band_member member where member.band_id = event.band_id
            and member.status = 'ACTIVE' and member.user_id = (
                select profile.user_id from tbl_musician_profile profile where profile.id = :targetId))))
        """;
    String SQL_MIDNIGHT_SECONDS = "extract(epoch from cast(:storageMidnight as time))";
    String SQL_START_SECONDS = "mod(extract(epoch from event.start_time) - " + SQL_MIDNIGHT_SECONDS + " + 86400, 86400)";
    String SQL_END_SECONDS = "mod(extract(epoch from event.end_time) - " + SQL_MIDNIGHT_SECONDS + " + 86400, 86400)";
    String SQL_NOW_SECONDS = "mod(extract(epoch from cast(:nowTime as time)) - " + SQL_MIDNIGHT_SECONDS + " + 86400, 86400)";
    String SQL_NOW = "(cast(:nowDate as date) + " + SQL_NOW_SECONDS + " * interval '1 second')";
    String SQL_EFFECTIVE_END = "(event.event_date + (case when event.end_time is null or " + SQL_END_SECONDS + " < " + SQL_START_SECONDS
            + " then " + SQL_START_SECONDS + " + 3600 else " + SQL_END_SECONDS + " end) * interval '1 second')";
    String SQL_PERIOD = " and ((:period = 'PAST' and " + SQL_EFFECTIVE_END + " <= " + SQL_NOW + ")"
            + " or (:period = 'CURRENT' and event.event_date <= :weekEnd and " + SQL_EFFECTIVE_END + " > " + SQL_NOW + ")"
            + " or (:period = 'FUTURE' and event.event_date > :weekEnd))";
    String SQL_ORDER = " order by case when :period = 'PAST' then event.event_date end desc,"
            + " case when :period = 'PAST' then " + SQL_START_SECONDS + " end desc,"
            + " event.event_date, " + SQL_START_SECONDS + ", event.id";

    @Query(value = "select event.id " + SQL_BASE + SQL_MUSICIAN + SQL_PERIOD + SQL_ORDER,
            countQuery = "select count(*) " + SQL_BASE + SQL_MUSICIAN + SQL_PERIOD, nativeQuery = true)
    Page<UUID> findForMusicianPeriod(@Param("targetId") UUID targetId, @Param("period") String period,
            @Param("nowDate") LocalDate nowDate, @Param("nowTime") LocalTime nowTime,
            @Param("storageMidnight") LocalTime storageMidnight, @Param("weekEnd") LocalDate weekEnd, Pageable pageable);

    @Query(value = "select event.id " + SQL_BASE + " and event.band_id = :targetId" + SQL_PERIOD + SQL_ORDER,
            countQuery = "select count(*) " + SQL_BASE + " and event.band_id = :targetId" + SQL_PERIOD, nativeQuery = true)
    Page<UUID> findForBandPeriod(@Param("targetId") UUID targetId, @Param("period") String period,
            @Param("nowDate") LocalDate nowDate, @Param("nowTime") LocalTime nowTime,
            @Param("storageMidnight") LocalTime storageMidnight, @Param("weekEnd") LocalDate weekEnd, Pageable pageable);

    String ELIGIBLE = """
        event.eventOrigin = com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE
        and event.performerApprovalStatus = com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus.APPROVED
        and event.venue.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
        and not exists (select request.id from EventPerformerRequest request where request.event.id = event.id
            and request.status = com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus.PENDING)
        """;
    String MUSICIAN = """
        (event.musicianProfile.id = :targetId or (event.band.id is not null and exists (
            select member.id from BandMember member where member.band.id = event.band.id
            and member.status = com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus.ACTIVE
            and member.user.id = (select profile.user.id from MusicianProfile profile where profile.id = :targetId))))
        """;

    @Query("select event.id from Event event where " + ELIGIBLE + " and " + MUSICIAN
            + " order by event.eventDate desc, event.startTime desc, event.id")
    Page<UUID> findForMusician(@Param("targetId") UUID targetId, Pageable pageable);

    @Query("select event.id from Event event where " + ELIGIBLE + " and event.band.id = :targetId"
            + " order by event.eventDate desc, event.startTime desc, event.id")
    Page<UUID> findForBand(@Param("targetId") UUID targetId, Pageable pageable);

    @EntityGraph(attributePaths = {"venue", "musicianProfile.user", "band"})
    @Query("select event from Event event where event.id in :ids and " + ELIGIBLE + " and " + MUSICIAN)
    List<Event> findMusicianDetails(@Param("ids") Collection<UUID> ids, @Param("targetId") UUID targetId);

    @EntityGraph(attributePaths = {"venue", "musicianProfile.user", "band"})
    @Query("select event from Event event where event.id in :ids and " + ELIGIBLE + " and event.band.id = :targetId")
    List<Event> findBandDetails(@Param("ids") Collection<UUID> ids, @Param("targetId") UUID targetId);

    @Query(value = "select band_id from tbl_event where id = :eventId", nativeQuery = true)
    Optional<UUID> findBandId(@Param("eventId") UUID eventId);

    @Query("select count(event) > 0 from Event event where event.id = :eventId and " + ELIGIBLE + " and " + MUSICIAN)
    boolean eligibleForMusician(@Param("eventId") UUID eventId, @Param("targetId") UUID targetId);

    @Query("select count(event) > 0 from Event event where event.id = :eventId and " + ELIGIBLE + " and event.band.id = :targetId")
    boolean eligibleForBand(@Param("eventId") UUID eventId, @Param("targetId") UUID targetId);
}
