package com.berkayb.soundconnect.modules.event.publication;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import java.util.Collection;
import java.util.List;

public interface EventMemberPublicationRepository extends JpaRepository<EventMemberPublication, EventMemberPublication.Id> {
    @Query("select choice from EventMemberPublication choice where choice.id.musicianProfileId = :profileId"
            + " and choice.id.eventId in :eventIds")
    List<EventMemberPublication> findPageChoices(@Param("profileId") UUID profileId,
            @Param("eventIds") Collection<UUID> eventIds);

    /** Caller holds the band parent write lock. Keep rows/revisions to reject old clients after rejoin. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        with locked_events as materialized (
            select id from tbl_event where band_id = :bandId order by id for update
        )
        insert into event_member_publications(event_id, musician_profile_id, visible, version)
        select event.id, profile.id, false, 1
        from locked_events event cross join tbl_musician_profile profile
        where profile.user_id = :userId
        on conflict (event_id, musician_profile_id) do update
        set visible = false, version = event_member_publications.version + 1
        """, nativeQuery = true)
    int hideForBandMember(@Param("bandId") UUID bandId, @Param("userId") UUID userId);
}
