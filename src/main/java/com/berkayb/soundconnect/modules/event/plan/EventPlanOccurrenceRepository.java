package com.berkayb.soundconnect.modules.event.plan;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import java.time.LocalDate;
import java.util.*;

public interface EventPlanOccurrenceRepository extends JpaRepository<EventPlanOccurrence, EventPlanOccurrence.Id> {
    @Query("select new com.berkayb.soundconnect.modules.event.plan.EventPlanPreviewOccurrence("
            + "o.id.scheduledDate,coalesce(e.eventDate,o.eventDate),o.status,e.id,e.startTime)"
            + " from EventPlanOccurrence o left join Event e on e.id=o.eventId"
            + " where o.id.planId=:planId and (o.id.scheduledDate between :from and :through"
            + " or coalesce(e.eventDate,o.eventDate) between :from and :through) order by o.id.scheduledDate")
    List<EventPlanPreviewOccurrence> findPreviewWindow(@Param("planId") UUID planId,
            @Param("from") LocalDate from, @Param("through") LocalDate through);
    @Query("select new com.berkayb.soundconnect.modules.event.plan.EventPlanStart(e.eventDate,e.startTime)"
            + " from EventPlanOccurrence o, Event e where o.id.planId=:planId and o.eventId=e.id"
            + " and o.status=com.berkayb.soundconnect.modules.event.plan.EventPlanOccurrenceStatus.GENERATED"
            + " and e.eventDate>=:today")
    List<EventPlanStart> findGeneratedStarts(@Param("planId") UUID planId, @Param("today") LocalDate today);
    @Query("select o.overrideBandId from EventPlanOccurrence o where o.id.planId=:planId and o.id.scheduledDate=:date")
    Optional<UUID> findOverrideBandId(@Param("planId") UUID planId, @Param("date") LocalDate date);
    @Modifying(flushAutomatically = true)
    @Query(value = """
        insert into event_member_publications(event_id,musician_profile_id,visible,version)
        select e.id,p.id,false,1 from tbl_event e join tbl_band_member m on m.band_id=e.band_id
        join tbl_musician_profile p on p.user_id=m.user_id where e.id=:eventId and m.status='ACTIVE'
        on conflict(event_id,musician_profile_id) do update set visible=false,version=event_member_publications.version+1
        """, nativeQuery = true)
    int resetMemberPublications(@Param("eventId") UUID eventId);
    @Query("select o.id.planId from EventPlanOccurrence o where o.eventId = :eventId")
    Optional<UUID> findPlanIdByEventId(@Param("eventId") UUID eventId);
    Optional<EventPlanOccurrence> findByEventId(UUID eventId);
    List<EventPlanOccurrence> findByIdPlanIdAndEventDateGreaterThanEqualOrderByIdScheduledDate(UUID planId, LocalDate from);
    Page<EventPlanOccurrence> findByIdPlanIdOrderByIdScheduledDateDesc(UUID planId, Pageable pageable);
}
