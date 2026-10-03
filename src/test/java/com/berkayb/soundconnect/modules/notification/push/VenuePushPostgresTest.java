package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.push.transport.PushEnvelope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.berkayb.soundconnect.modules.notification.enums.NotificationType.*;

/** Real isolated PostgreSQL source/job/capability checks; never uses the application datasource. */
@EnabledIfSystemProperty(named="push.test.jdbc-url",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/soundconnect_push_test")
class VenuePushPostgresTest {
    // Compose the isolated foundation fixture without inheriting/recounting its tests.
    private final PushFoundationPostgresTest f = new PushFoundationPostgresTest();
    private UUID owner, musician, founder, member, venue, profile, band;
    private VenuePushEligibility eligibility;
    private final Map<UUID,UUID> installations = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        f.setup();
        owner=f.user; musician=f.other; founder=user(); member=user(); venue=UUID.randomUUID(); profile=UUID.randomUUID(); band=UUID.randomUUID();
        f.jdbc.execute("create table if not exists tbl_venues(id uuid primary key,owner_id uuid,status text)");
        f.jdbc.execute("create table tbl_musician_profile(id uuid primary key,user_id uuid)");
        f.jdbc.execute("create table tbl_band(id uuid primary key)");
        f.jdbc.execute("create table tbl_band_member(id uuid primary key,band_id uuid,user_id uuid,status text,band_role text)");
        f.jdbc.execute("create table artist_venue_connection_requests(id uuid primary key,venue_id uuid,musician_profile_id uuid,band_id uuid,status text,request_by_type text)");
        f.jdbc.execute("create table tbl_event(id uuid primary key,venue_id uuid,organizer_user_id uuid,event_origin text,event_date date,start_time time,performer_approval_status text,musician_profile_id uuid,band_id uuid)");
        f.jdbc.execute("create table event_performer_requests(id uuid primary key,event_id uuid,musician_profile_id uuid,band_id uuid,status text,request_purpose text)");
        f.jdbc.execute("create table event_plans(id uuid primary key,venue_id uuid,organizer_user_id uuid,musician_profile_id uuid,band_id uuid,status text,consent_status text,consent_revision bigint,start_date date,until_date date,start_time time,weekday_mask int,excluded_dates jsonb)");
        f.jdbc.update("insert into tbl_venues values(?,?,'APPROVED')",venue,owner);
        f.jdbc.update("insert into tbl_musician_profile values(?,?)",profile,musician);
        f.jdbc.update("insert into tbl_band values(?)",band);
        f.jdbc.update("insert into tbl_band_member values(?,?,?,'ACTIVE','FOUNDER'),(?,?,?,'ACTIVE','MEMBER')",UUID.randomUUID(),band,founder,UUID.randomUUID(),band,member);
        f.properties.setAllowedTypes(EnumSet.copyOf(VenuePushPresentation.TYPES));
        f.properties.getAllowedTypes().add(DM_NEW_MESSAGE);
        eligibility=new VenuePushEligibility(f.sql,f.clock);
        for (UUID user:List.of(owner,musician,founder,member)) register(user,VenuePushPresentation.CAPABILITY,PushDeviceService.Platform.ANDROID);
    }
    @Test void v6PreparesAllSixVenueTypesAndDm() {
        f.jdbc.update("update tbl_push_device set presentation_version=?",MediaPushPresentation.CAPABILITY);
        sixTypesDeriveOnlyValidatedVariantsAndPreservePrivateData();
        var n=notification(musician,DM_NEW_MESSAGE,Map.of("conversationId",UUID.randomUUID().toString()));
        assertThat(prepare(n).orElseThrow().data()).containsEntry("presentationVersion","ANDROID_DM_V1");
    }

    @AfterEach void cleanup() { f.cleanup(); }
    private UUID user() {
        UUID id=UUID.randomUUID(); f.jdbc.update("insert into tbl_user values(?,null,'ACTIVE',true)",id); return id;
    }
    private UUID register(UUID user,String capability,PushDeviceService.Platform platform) {
        UUID installation=UUID.randomUUID();
        f.tx.executeWithoutResult(s->f.devices.register(user,installation,new PushDeviceService.Registration(
                "fixture-"+installation,platform,PushDeviceService.Permission.AUTHORIZED,"venue-test",1L,capability)));
        installations.put(user,installation); return installation;
    }
    private Notification notification(UUID recipient,NotificationType type,Map<String,Object> payload) {
        var n=Notification.builder().sourceEventId(UUID.randomUUID()).recipientId(recipient).type(type)
                .title("Private source name").message("Private request reason").payload(Map.copyOf(payload))
                .occurredAt(f.clock.instant()).read(false).build();
        ReflectionTestUtils.setField(n,"id",UUID.randomUUID());
        f.jdbc.update("insert into tbl_notification(id,recipient_id) values(?,?)",n.getId(),recipient);
        f.inbox.put(n.getId(),n); return n;
    }
    private void plan(Notification n) { f.tx.executeWithoutResult(s->f.planner.plan(new NotificationPersisted(n))); }
    private PushDeliveryStore.Claim claim() { return f.store.claimNext().orElseThrow(); }
    private Optional<PushEnvelope> prepare(Notification n) { plan(n); return f.store.prepare(claim()); }
    private Optional<VenuePushPresentation.Variant> eligible(Notification n) { return f.tx.execute(s->eligibility.resolve(n)); }
    private Notification connection(boolean isBand,String direction,NotificationType type,UUID recipient) {
        UUID id=UUID.randomUUID();
        String status=type==ARTIST_VENUE_LINK_APPLICATION_REQUEST?"PENDING":type==ARTIST_VENUE_LINK_APPLICATION_ACCEPT?"ACCEPTED":"REJECTED";
        String action=type==ARTIST_VENUE_LINK_APPLICATION_REQUEST?"REQUEST_CREATED":type==ARTIST_VENUE_LINK_APPLICATION_ACCEPT?"REQUEST_ACCEPTED":"REQUEST_REJECTED";
        f.jdbc.update("insert into artist_venue_connection_requests values(?,?,?,?,?,?)",id,venue,isBand?null:profile,isBand?band:null,status,direction);
        Map<String,Object> p=new HashMap<>(Map.of("module","ARTIST_VENUE","action",action,"requestId",id.toString(),"venueId",venue.toString(),"requestByType",direction,"status",status));
        p.put(isBand?"bandId":"musicianProfileId",(isBand?band:profile).toString()); p.put("actorAvatarUrl","https://private.example/should-not-send");
        return notification(recipient,type,p);
    }
    private Notification event(boolean isBand,String purpose,NotificationType type,UUID recipient) {
        UUID id=UUID.randomUUID(), request=UUID.randomUUID();
        String status=type==EVENT_PERFORMER_APPROVAL_REQUESTED?"PENDING":type==EVENT_PERFORMER_APPROVED?"ACCEPTED":"REJECTED";
        String action=type==EVENT_PERFORMER_APPROVAL_REQUESTED?"APPROVAL_REQUESTED":type==EVENT_PERFORMER_APPROVED?"APPROVED":"REJECTED";
        boolean linked="PROFILE_VISIBILITY".equals(purpose)||type==EVENT_PERFORMER_APPROVED;
        f.jdbc.update("insert into tbl_event values(?,?,?,'VENUE','2026-09-23','18:00',?,?,?)",id,venue,owner,linked?"APPROVED":status,linked&&!isBand?profile:null,linked&&isBand?band:null);
        f.jdbc.update("insert into event_performer_requests values(?,?,?,?,?,?)",request,id,isBand?null:profile,isBand?band:null,status,purpose);
        Map<String,Object> p=new HashMap<>(Map.of("module","EVENT_PERFORMER","action",action,"eventId",id.toString(),"requestId",request.toString(),"venueId",venue.toString(),"status",status,"requestPurpose",purpose));
        p.put(isBand?"bandId":"musicianProfileId",(isBand?band:profile).toString());
        return notification(recipient,type,p);
    }
    private Notification planSource(boolean isBand,String action,UUID recipient) {
        UUID id=UUID.randomUUID();
        String status=switch(action) {case "REQUESTED"->"PENDING";case "ACCEPT"->"ACCEPTED";case "REJECT"->"REJECTED";default->"WITHDRAWN";};
        var type="REQUESTED".equals(action)?EVENT_PERFORMER_APPROVAL_REQUESTED:"ACCEPT".equals(action)?EVENT_PERFORMER_APPROVED:EVENT_PERFORMER_REJECTED;
        f.jdbc.update("insert into event_plans values(?,?,?,?,?,'ACTIVE',?,7,'2026-09-23',null,'18:00',127,'[]')",id,venue,owner,isBand?null:profile,isBand?band:null,status);
        return notification(recipient,type,Map.of("module","EVENT_PLAN","planId",id.toString(),"venueId",venue.toString(),"action",action,"consentStatus",status,"consentRevision",7,"targetType",isBand?"BAND":"MUSICIAN","targetId",(isBand?band:profile).toString()));
    }
    private UUID id(Notification n,String field) { return UUID.fromString(n.getPayload().get(field).toString()); }
    private Notification changed(Notification n,String key,Object value) {
        var p=new HashMap<>(n.getPayload()); p.put(key,value);
        return notification(n.getRecipientId(),n.getType(),p);
    }

    @Test void sixTypesDeriveOnlyValidatedVariantsAndPreservePrivateData() {
        for(boolean isBand:List.of(false,true)) for(String direction:List.of("VENUE",isBand?"BAND":"ARTIST"))
            for(var type:List.of(ARTIST_VENUE_LINK_APPLICATION_REQUEST,ARTIST_VENUE_LINK_APPLICATION_ACCEPT,ARTIST_VENUE_LINK_APPLICATION_REJECT)) {
                boolean ownerRecipient=(type==ARTIST_VENUE_LINK_APPLICATION_REQUEST)!=direction.equals("VENUE");
                var n=connection(isBand,direction,type,ownerRecipient?owner:isBand?member:musician);
                var envelope=prepare(n).orElseThrow();
                assertThat(envelope.data()).containsEntry("displayVariant","DEFAULT").containsEntry("presentationVersion",VenuePushPresentation.VERSION);
                assertThat(envelope.data()).containsOnlyKeys("notificationId","recipientId","type","presentationVersion","displayVariant","sentAt","expiresAt");
                assertThat(envelope.data().toString()+envelope.title()+envelope.body()).doesNotContain("Private", "private.example");
            }
        for(boolean isBand:List.of(false,true)) for(String purpose:List.of("PERFORMER_CONSENT","PROFILE_VISIBILITY"))
            for(var type:List.of(EVENT_PERFORMER_APPROVAL_REQUESTED,EVENT_PERFORMER_APPROVED,EVENT_PERFORMER_REJECTED)) {
                var n=event(isBand,purpose,type,type==EVENT_PERFORMER_APPROVAL_REQUESTED?(isBand?founder:musician):owner);
                assertThat(prepare(n).orElseThrow().data()).containsEntry("displayVariant",purpose.equals("PROFILE_VISIBILITY")?"PROFILE_VISIBILITY":"DEFAULT");
            }
        for(boolean isBand:List.of(false,true)) for(String action:List.of("REQUESTED","ACCEPT","REJECT","WITHDRAW")) {
            var n=planSource(isBand,action,action.equals("REQUESTED")?(isBand?founder:musician):owner);
            if(action.equals("WITHDRAW")) f.jdbc.update("update event_plans set status='STOPPED' where id=?",id(n,"planId"));
            assertThat(prepare(n).orElseThrow().data()).containsEntry("displayVariant",action.equals("WITHDRAW")?"PLAN_WITHDRAWN":"PLAN_CONSENT");
        }
    }

    @Test void capabilityPlanningAndFinalDowngradeNeverFallBackToAutomaticOsPush() {
        register(owner,"ANDROID_DM_V1",PushDeviceService.Platform.ANDROID);
        register(owner,null,PushDeviceService.Platform.ANDROID);
        register(owner,null,PushDeviceService.Platform.IOS);
        var n=connection(false,"ARTIST",ARTIST_VENUE_LINK_APPLICATION_REQUEST,owner); plan(n); plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(1);
        var claim=claim();
        f.jdbc.update("update tbl_push_device set presentation_version='ANDROID_DM_V1' where installation_id=?",claim.installationId());
        assertThat(f.store.prepare(claim)).isEmpty();
        assertThat(f.jdbc.queryForObject("select last_error_code from tbl_push_delivery",String.class)).isEqualTo("PRESENTATION_UNAVAILABLE");
    }

    @Test void currentDmPayloadRemainsDmV1ForBothSupportedCapabilities() {
        for(String capability:List.of("ANDROID_DM_V1",VenuePushPresentation.CAPABILITY,VenueApplicationPushPresentation.CAPABILITY,StudioPushPresentation.CAPABILITY)) {
            f.jdbc.update("update tbl_push_device set presentation_version=? where user_id=?",capability,musician);
            var n=notification(musician,DM_NEW_MESSAGE,Map.of("conversationId",UUID.randomUUID().toString()));
            assertThat(prepare(n).orElseThrow().data()).containsEntry("presentationVersion","ANDROID_DM_V1").containsKey("conversationId").doesNotContainKey("displayVariant");
        }
    }

    @Test void queuedConnectionRequestAndAcceptanceAreSuppressedAfterCancelOrDisconnect() {
        for(var type:List.of(ARTIST_VENUE_LINK_APPLICATION_REQUEST,ARTIST_VENUE_LINK_APPLICATION_ACCEPT)) {
            var n=connection(false,"ARTIST",type,type==ARTIST_VENUE_LINK_APPLICATION_REQUEST?owner:musician); plan(n);
            f.jdbc.update("update artist_venue_connection_requests set status='REJECTED' where id=?",id(n,"requestId"));
            assertThat(f.store.prepare(claim())).isEmpty();
        }
    }

    @Test void sourceIdentityMembershipAuthorityAndAccountsAreFresh() {
        var n=connection(true,"VENUE",ARTIST_VENUE_LINK_APPLICATION_REQUEST,member);
        assertThat(eligible(n)).isPresent();
        f.jdbc.update("update tbl_band_member set status='LEFT' where user_id=?",member);
        assertThat(eligible(n)).isEmpty();
        var invitation=event(true,"PERFORMER_CONSENT",EVENT_PERFORMER_APPROVAL_REQUESTED,member);
        assertThat(eligible(invitation)).isEmpty();
        var founderInvitation=event(true,"PERFORMER_CONSENT",EVENT_PERFORMER_APPROVAL_REQUESTED,founder);
        assertThat(eligible(founderInvitation)).isPresent();
        f.jdbc.update("update tbl_band_member set band_role='MANAGER' where user_id=?",founder);
        assertThat(eligible(founderInvitation)).isEmpty();
        var personal=connection(false,"VENUE",ARTIST_VENUE_LINK_APPLICATION_REQUEST,musician);
        assertThat(eligible(personal)).isPresent();
        f.jdbc.update("update tbl_user set status='PASSIVE' where id=?",owner);
        assertThat(eligible(personal)).isEmpty();
        f.jdbc.update("update tbl_user set status='ACTIVE' where id=?",owner);
        f.jdbc.update("update tbl_venues set status='REJECTED' where id=?",venue);
        assertThat(eligible(personal)).isEmpty();
    }

    @Test void missingForeignChangedPurposeExpiredAndSupersededSourcesFailClosed() {
        var e=event(false,"PERFORMER_CONSENT",EVENT_PERFORMER_APPROVAL_REQUESTED,musician);
        assertThat(eligible(changed(e,"venueId",UUID.randomUUID().toString()))).isEmpty();
        assertThat(eligible(changed(e,"requestPurpose","PROFILE_VISIBILITY"))).isEmpty();
        assertThat(eligible(changed(e,"module","OTHER"))).isEmpty();
        assertThat(eligible(changed(e,"bandId",band.toString()))).isEmpty();
        f.jdbc.update("update tbl_event set event_origin='MUSICIAN' where id=?",id(e,"eventId"));
        assertThat(eligible(e)).isEmpty();
        f.jdbc.update("update tbl_event set event_origin='VENUE',event_date='2026-09-21' where id=?",id(e,"eventId"));
        assertThat(eligible(e)).isEmpty();
        f.jdbc.update("delete from tbl_event where id=?",id(e,"eventId"));
        assertThat(eligible(e)).isEmpty();
        var plan=planSource(false,"REQUESTED",musician);
        assertThat(eligible(changed(plan,"consentRevision",6))).isEmpty();
        f.jdbc.update("update event_plans set consent_revision=8 where id=?",id(plan,"planId"));
        assertThat(eligible(plan)).isEmpty();
        var withdrawn=planSource(false,"WITHDRAW",owner);
        assertThat(eligible(changed(withdrawn,"action","REJECT"))).isEmpty();
        f.jdbc.update("update event_plans set organizer_user_id=? where id=?",musician,id(withdrawn,"planId"));
        assertThat(eligible(withdrawn)).isEmpty();
    }

    @Test void sourceLockContentionIsRetriedNotTerminallySuppressed() throws Exception {
        var n=connection(false,"ARTIST",ARTIST_VENUE_LINK_APPLICATION_REQUEST,owner); plan(n); var job=claim();
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var writer=executor.submit(()->f.tx.executeWithoutResult(s->{
                f.jdbc.queryForObject("select id from artist_venue_connection_requests where id=? for update",UUID.class,id(n,"requestId"));
                locked.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test lock timed out"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }));
            try {
                assertThat(locked.await(3,TimeUnit.SECONDS)).isTrue();
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),()->
                        assertThatThrownBy(()->f.store.prepare(job)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                                .satisfies(error->assertThat(((java.sql.SQLException)((org.springframework.dao.DataAccessException)error)
                                        .getMostSpecificCause()).getSQLState()).isEqualTo("55P03")));
                f.store.transientFailure(job);
                assertThat(f.jdbc.queryForObject("select status from tbl_push_delivery",String.class)).isEqualTo("PENDING");
            } finally { release.countDown(); }
            writer.get(3,TimeUnit.SECONDS);
        }
        f.jdbc.update("update tbl_push_delivery set next_attempt_at=?",java.sql.Timestamp.from(f.clock.instant()));
        assertThat(f.store.prepare(claim())).isPresent();
    }

    @Test void forwardMigrationPreservesOldDevicesIsRepeatableAndRequiresValidatedCapability() throws Exception {
        UUID old=register(musician,"ANDROID_DM_V1",PushDeviceService.Platform.ANDROID);
        var before=f.jdbc.queryForList("select * from tbl_push_device order by installation_id");
        String migration=Files.readString(Path.of("scripts/db/2026-09-24-push-native-venue-capability.sql"));
        f.jdbc.execute(migration); f.jdbc.execute(migration);
        assertThat(f.jdbc.queryForList("select * from tbl_push_device order by installation_id")).isEqualTo(before);
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("venue-application-notifications");
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-venue-application-notifications.sql")));
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-push-native-studio-capability.sql")));
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-28-push-native-follow-capability.sql")));
        assertThatThrownBy(() -> f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-table-capability");
        assertThatThrownBy(()->register(musician,VenuePushPresentation.CAPABILITY,PushDeviceService.Platform.IOS))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        assertThatThrownBy(()->f.jdbc.update("update tbl_push_device set platform='IOS' where installation_id=?",old))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        f.jdbc.update("delete from soundconnect_schema_migrations where migration_id='2026-09-24-push-native-venue-capability'");
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-venue-capability");
        f.jdbc.execute(migration);
        f.jdbc.execute("alter table tbl_push_device drop constraint ck_push_device_presentation");
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-venue-capability");
    }
}
