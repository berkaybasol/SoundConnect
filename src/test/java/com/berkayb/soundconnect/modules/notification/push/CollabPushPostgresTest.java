package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.support.CollabNotificationIdentity;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Disposable PostgreSQL only. Minimal relational fixture; not HTTP/broker/FCM acceptance. */
@Testcontainers
class CollabPushPostgresTest {
    private static final List<String> JDBC_OPT_IN_BEFORE_CLASS=PushFoundationPostgresTest.jdbcOptInSettings();
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.4-alpine")
        .withDatabaseName("soundconnect_push_test").withUsername("push_test").withPassword("isolated-fixture");
    final PushFoundationPostgresTest f=new PushFoundationPostgresTest();
    final ObjectMapper json=new ObjectMapper();
    UUID listing,application,job,review,report,owner,applicant,publisherActor,applicantActor,role;
    @BeforeEach void setup() throws Exception {
        f.setup("jdbc:postgresql://127.0.0.1:"+DB.getMappedPort(5432)+"/soundconnect_push_test",DB.getUsername(),DB.getPassword());
        f.properties.setMaxDevicesPerUser(30); f.properties.setAllowedTypes(CollabPushPresentation.TYPES);
        f.jdbc.execute("alter table tbl_notification drop constraint legacy_notification_type");
        f.jdbc.execute("alter table tbl_notification add column source_event_id uuid, add column payload jsonb, add column occurred_at timestamptz");
        f.jdbc.execute("create table tbl_notification_receipt(source_event_id uuid,recipient_id uuid)");
        f.jdbc.execute("create table tbl_role(id uuid primary key,name text); create table user_roles(user_id uuid,role_id uuid)");
        for(String name:List.of("tbl_musician_profile","tbl_studio_profile","tbl_organizer_profile","tbl_producer_profile","\"tbl_listener-profile\""))
            f.jdbc.execute("create table "+name+"(id uuid primary key,user_id uuid)");
        f.jdbc.execute("create table tbl_band_member(band_id uuid,user_id uuid,status text,band_role text)");
        f.jdbc.execute("create table tbl_collab_actor(id uuid primary key,profile_type text,source_profile_id uuid,active boolean)");
        f.jdbc.execute("create table tbl_collab(id uuid primary key,owner_user_id uuid,publisher_actor_id uuid,status text,closure_reason text,expires_at timestamptz,closed_at timestamptz)");
        f.jdbc.execute("create table tbl_collab_application(id uuid primary key,collab_id uuid,applicant_actor_id uuid,applicant_user_id uuid,status text,submitted_at timestamptz,status_changed_at timestamptz,decided_at timestamptz)");
        f.jdbc.execute("create table tbl_collab_job(id uuid primary key,collab_id uuid,application_id uuid,publisher_user_id uuid,applicant_user_id uuid,publisher_actor_id uuid,applicant_actor_id uuid,status text,publisher_confirmed_at timestamptz,applicant_confirmed_at timestamptz,completed_at timestamptz)");
        f.jdbc.execute("create table tbl_collab_review(id uuid primary key,job_id uuid,reviewer_user_id uuid,reviewer_actor_id uuid,target_actor_id uuid,submitted_at timestamptz)");
        f.jdbc.execute("create table tbl_collab_report(id uuid primary key,collab_id uuid,reporter_user_id uuid,status text,review_decision text,reviewed_at timestamptz)");
        f.jdbc.execute("create table tbl_collab_notification_outbox(event_id uuid primary key,recipient_id uuid,notification_type text,payload jsonb,occurred_at timestamptz)");
        role=UUID.randomUUID(); f.jdbc.update("insert into tbl_role values(?,'ROLE_MUSICIAN')",role);
        for(UUID user:List.of(f.user,f.other)) {
            f.jdbc.update("insert into user_roles values(?,?)",user,role);
            f.jdbc.update("insert into tbl_musician_profile values(?,?)",UUID.randomUUID(),user);
        }
        f.jdbc.execute("insert into soundconnect_schema_migrations(migration_id) values('2026-09-30-table-notification-target')");
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-10-01-push-native-table-capability.sql")));
        migration();
        ReflectionTestUtils.setField(f.store,"policy",new NotificationDeliveryPolicy(new AccountDeliveryFence(f.sql),f.sql,f.transactionManager,mock(AfterCommitDeliveryExecutor.class)));
    }
    @AfterEach void cleanup(){ f.cleanup(); }
    @Test void containerFixtureDoesNotChangeJvmJdbcOptIn() {
        assertThat(PushFoundationPostgresTest.jdbcOptInSettings().equals(JDBC_OPT_IN_BEFORE_CLASS))
            .as("Container fixture must preserve the process-wide JDBC opt-in settings").isTrue();
    }
    void migration() throws Exception { f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-10-01-push-native-collab-capability.sql"))); }
    UUID register(String version) {
        var id=UUID.randomUUID();
        f.tx.executeWithoutResult(s->f.devices.register(f.user,id,new PushDeviceService.Registration("collab-fixture-"+id,PushDeviceService.Platform.ANDROID,PushDeviceService.Permission.AUTHORIZED,"test",1L,version)));
        return id;
    }
    Notification fixture(String action,String variant,boolean publisherRecipient) throws Exception {
        String type="COLLAB_"+action;
        boolean ownerEvent=Set.of("APPLICATION_RECEIVED","APPLICATION_WITHDRAWN","LISTING_EXPIRED","LISTING_REMOVED").contains(action)||publisherRecipient;
        owner=ownerEvent?f.user:f.other; applicant=ownerEvent?f.other:f.user;
        listing=UUID.randomUUID(); application=UUID.randomUUID(); job=UUID.randomUUID();review=UUID.randomUUID();report=UUID.randomUUID();
        publisherActor=UUID.randomUUID();applicantActor=UUID.randomUUID();
        for(var pair:List.of(Map.entry(publisherActor,owner),Map.entry(applicantActor,applicant))) {
            UUID profile=f.jdbc.queryForObject("select id from tbl_musician_profile where user_id=?",UUID.class,pair.getValue());
            f.jdbc.update("insert into tbl_collab_actor values(?,'MUSICIAN',?,true)",pair.getKey(),profile);
        }
        var now=Timestamp.from(f.clock.instant());
        String listingStatus=action.equals("LISTING_EXPIRED")?"EXPIRED":action.equals("LISTING_REMOVED")?"CLOSED":"OPEN";
        f.jdbc.update("insert into tbl_collab values(?,?,?,?,?,?,?)",listing,owner,publisherActor,listingStatus,action.equals("LISTING_REMOVED")?"ADMIN_REMOVED":"EXPIRED",now,now);
        String state=switch(action){case "APPLICATION_RECEIVED"->"PENDING";case "APPLICATION_REJECTED"->"REJECTED";case "APPLICATION_WITHDRAWN"->"WITHDRAWN_BY_APPLICANT";case "APPLICATION_INVALIDATED"->"INVALIDATED_BY_LISTING_CLOSURE";default->"ACCEPTED";};
        f.jdbc.update("insert into tbl_collab_application values(?,?,?,?,?,?,?,?)",application,listing,applicantActor,applicant,state,now,now,now);
        f.jdbc.update("insert into tbl_collab_job values(?,?,?,?,?,?,?,?,?,?,?)",job,listing,application,owner,applicant,publisherActor,applicantActor,action.equals("JOB_COMPLETION_REQUESTED")?"ACTIVE":"COMPLETED",now,now,now);
        f.jdbc.update("insert into tbl_collab_review values(?,?,?,?,?,?)",review,job,publisherRecipient?applicant:owner,publisherRecipient?applicantActor:publisherActor,publisherRecipient?publisherActor:applicantActor,now);
        f.jdbc.update("insert into tbl_collab_report values(?,?,?,?,?,?)",report,listing,f.user,"DISMISS".equals(variant)?"DISMISSED":"ACTIONED",variant,now);
        var payload=new HashMap<String,Object>(Map.of("module","COLLAB","action",action,"listingId",listing.toString()));
        if(action.startsWith("APPLICATION_")) payload.put("applicationId",application.toString());
        if(action.equals("APPLICATION_ACCEPTED")||action.startsWith("JOB_")||action.equals("REVIEW_RECEIVED")) payload.put("jobId",job.toString());
        if(action.equals("REVIEW_RECEIVED")) payload.put("reviewId",review.toString());
        if(action.equals("REPORT_RESOLVED")){payload.put("reportId",report.toString());payload.put("decision",variant);}
        var n=Notification.builder().sourceEventId(UUID.randomUUID()).recipientId(f.user).type(NotificationType.valueOf(type)).title("Private title").message("Private phone and note").payload(payload).occurredAt(f.clock.instant()).read(false).build();
        ReflectionTestUtils.setField(n,"id",UUID.randomUUID());
        f.jdbc.update("insert into tbl_notification(id,recipient_id,type,source_event_id,payload,occurred_at) values(?,?,?,?,?::jsonb,?)",n.getId(),f.user,type,n.getSourceEventId(),json.writeValueAsString(payload),now);
        f.jdbc.update("insert into tbl_collab_notification_outbox values(?,?,?,?::jsonb,?)",n.getSourceEventId(),f.user,type,json.writeValueAsString(payload),now);
        f.jdbc.update("insert into tbl_notification_receipt values(?,?)",n.getSourceEventId(),f.user);
        f.inbox.put(n.getId(),n);return n;
    }
    void plan(Notification n){ f.tx.executeWithoutResult(s->f.planner.plan(new NotificationPersisted(n))); }
    @Test void upgradedV10KeepsExistingCollabWireAndV9Delivery() throws Exception {
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-10-01-push-native-overthinking-capability.sql")));
        var expected=Set.of(register("ANDROID_NATIVE_V10"),register("ANDROID_NATIVE_V9"));
        var n=fixture("APPLICATION_RECEIVED","DEFAULT",false);plan(n);
        var actual=new HashSet<UUID>();
        for(int i=0;i<2;i++) {
            var claim=f.store.claimNext().orElseThrow();actual.add(claim.installationId());
            assertThat(f.store.prepare(claim).orElseThrow().data())
                .containsEntry("presentationVersion","ANDROID_COLLAB_V1").containsEntry("displayVariant","DEFAULT");
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(2);
    }
    @ParameterizedTest @CsvSource({"APPLICATION_RECEIVED,DEFAULT,false","APPLICATION_ACCEPTED,DEFAULT,false","APPLICATION_REJECTED,DEFAULT,false","APPLICATION_WITHDRAWN,DEFAULT,false","APPLICATION_INVALIDATED,DEFAULT,false","LISTING_EXPIRED,DEFAULT,false","JOB_COMPLETION_REQUESTED,DEFAULT,false","JOB_COMPLETION_REQUESTED,DEFAULT,true","JOB_COMPLETED,DEFAULT,false","JOB_COMPLETED,DEFAULT,true","REVIEW_RECEIVED,DEFAULT,false","REVIEW_RECEIVED,DEFAULT,true","LISTING_REMOVED,DEFAULT,false","REPORT_RESOLVED,REMOVE_LISTING,false","REPORT_RESOLVED,DISMISS,false"})
    void exactOccurrencesOnlyPlanV9AndPrepareMinimalWire(String action,String variant,boolean publisherRecipient) throws Exception {
        var v9=register("ANDROID_NATIVE_V9");register("ANDROID_NATIVE_V8");register(null);
        var n=fixture(action,variant,publisherRecipient);plan(n);plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isOne();
        var claim=f.store.claimNext().orElseThrow();assertThat(claim.installationId()).isEqualTo(v9);
        var wire=f.store.prepare(claim).orElseThrow().data();
        assertThat(wire).containsEntry("type",n.getType().name()).containsEntry("presentationVersion","ANDROID_COLLAB_V1").containsEntry("displayVariant",variant);
        assertThat(wire.keySet()).containsExactlyInAnyOrder("notificationId","recipientId","type","presentationVersion","displayVariant","sentAt","expiresAt");
        assertThat(wire.toString()).doesNotContain("Private",listing.toString(),application.toString(),job.toString(),review.toString(),report.toString());
        assertThat(CollabNotificationIdentity.resolve(f.sql,f.user,n.getId(),false)).isPresent();
        assertThat(CollabNotificationIdentity.resolve(f.sql,f.other,n.getId(),false)).isEmpty();
    }
    void mutate(String change,Notification n) {
        switch(change){
            case "source" -> f.jdbc.execute("delete from tbl_collab_notification_outbox");
            case "receipt" -> f.jdbc.execute("delete from tbl_notification_receipt");
            case "type" -> f.jdbc.execute("update tbl_collab_notification_outbox set notification_type='COLLAB_APPLICATION_REJECTED'");
            case "sourceTime" -> f.jdbc.execute("update tbl_collab_notification_outbox set occurred_at=occurred_at-interval '1 second'");
            case "recipient" -> f.jdbc.update("update tbl_notification set recipient_id=?",f.other);
            case "sourceRecipient" -> f.jdbc.update("update tbl_collab_notification_outbox set recipient_id=?",f.other);
            case "read" -> f.jdbc.execute("update tbl_notification set is_read=true");
            case "role" -> f.jdbc.execute("delete from user_roles");
            case "mixedRole" -> {var id=UUID.randomUUID();f.jdbc.update("insert into tbl_role values(?,'ROLE_LISTENER')",id);f.jdbc.update("insert into user_roles values(?,?)",f.user,id);}
            case "mixedProfile" -> f.jdbc.update("insert into \"tbl_listener-profile\" values(?,?)",UUID.randomUUID(),f.user);
            case "erased" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",f.user);
            case "ownerErased" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",owner);
            case "unverified" -> f.jdbc.update("update tbl_user set email_verified=false where id=?",f.user);
            case "inactiveActor" -> f.jdbc.execute("update tbl_collab_actor set active=false");
            case "actorUserMixup" -> f.jdbc.update("update tbl_collab set publisher_actor_id=?",owner);
            case "applicationCrossUser" -> f.jdbc.update("update tbl_collab_application set applicant_user_id=?",owner);
            case "applicationCrossListing" -> f.jdbc.update("update tbl_collab_application set collab_id=?",UUID.randomUUID());
            case "jobCrossApplication" -> f.jdbc.update("update tbl_collab_job set application_id=?",UUID.randomUUID());
            case "jobCrossUser" -> f.jdbc.update("update tbl_collab_job set applicant_user_id=?",owner);
            case "reviewCrossActor" -> f.jdbc.update("update tbl_collab_review set target_actor_id=?",publisherActor);
            case "reviewCrossJob" -> f.jdbc.update("update tbl_collab_review set job_id=?",UUID.randomUUID());
            case "reportCrossUser" -> f.jdbc.update("update tbl_collab_report set reporter_user_id=?",f.other);
            case "reportDecision" -> f.jdbc.execute("update tbl_collab_report set review_decision='DISMISS',status='DISMISSED'");
            case "reportFuture" -> f.jdbc.execute("update tbl_collab_report set reviewed_at=reviewed_at+interval '1 second'");
            case "downgrade" -> f.jdbc.execute("update tbl_push_device set presentation_version='ANDROID_NATIVE_V8'");
            case "generation" -> f.jdbc.execute("update tbl_push_device set generation=generation+1");
            case "deviceOwner" -> f.jdbc.update("update tbl_push_device set user_id=?",f.other);
            case "permission" -> f.jdbc.execute("update tbl_push_device set permission='DENIED'");
            case "preference" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(false,Set.of())));
            case "category" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(true,Set.of("COLLAB"))));
            case "expiry" -> f.jdbc.execute("update tbl_push_delivery set expires_at=expires_at+interval '1 day'");
            default -> throw new IllegalArgumentException(change);
        }
    }
    @ParameterizedTest @ValueSource(strings={"source","receipt","type","sourceTime","recipient","sourceRecipient","read","role","mixedRole","mixedProfile","erased","ownerErased","unverified","inactiveActor","actorUserMixup","applicationCrossUser","applicationCrossListing","preference","category","permission","downgrade"})
    void planRejectsMissingEvidenceAndCurrentAuthority(String change) throws Exception {
        register("ANDROID_NATIVE_V9");var n=fixture("APPLICATION_RECEIVED","DEFAULT",false);mutate(change,n);plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"source","receipt","type","sourceTime","recipient","sourceRecipient","read","role","mixedRole","mixedProfile","erased","ownerErased","unverified","inactiveActor","actorUserMixup","applicationCrossUser","applicationCrossListing","preference","category","permission","downgrade","generation","deviceOwner"})
    void sendRevalidatesAfterPlan(String change) throws Exception {
        register("ANDROID_NATIVE_V9");var n=fixture("APPLICATION_RECEIVED","DEFAULT",false);plan(n);var claim=f.store.claimNext().orElseThrow();mutate(change,n);
        assertThat(f.store.prepare(claim)).isEmpty();
    }
    @ParameterizedTest @CsvSource({"APPLICATION_ACCEPTED,jobCrossApplication","JOB_COMPLETED,jobCrossUser","REVIEW_RECEIVED,reviewCrossActor","REVIEW_RECEIVED,reviewCrossJob","REPORT_RESOLVED,reportCrossUser","REPORT_RESOLVED,reportDecision","REPORT_RESOLVED,reportFuture"})
    void crossDomainIdentityRejectedAtBothStagesAndInbox(String action,String change) throws Exception {
        register("ANDROID_NATIVE_V9");var n=fixture(action,action.equals("REPORT_RESOLVED")?"REMOVE_LISTING":"DEFAULT",false);plan(n);
        var claim=f.store.claimNext().orElseThrow();mutate(change,n);assertThat(f.store.prepare(claim)).isEmpty();
        assertThat(CollabPushPresentation.resolve(n,f.sql)).isEmpty();assertThat(CollabNotificationIdentity.resolve(f.sql,f.user,n.getId(),false)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"APPLICATION_RECEIVED","APPLICATION_ACCEPTED","APPLICATION_REJECTED","APPLICATION_WITHDRAWN","APPLICATION_INVALIDATED","LISTING_EXPIRED","JOB_COMPLETION_REQUESTED","JOB_COMPLETED","REVIEW_RECEIVED","LISTING_REMOVED","REPORT_RESOLVED"})
    void purgeRetainsOwnedHistoricalInboxButNeverCreatesNativeEvidence(String action) throws Exception {
        var n=fixture(action,action.equals("REPORT_RESOLVED")?"DISMISS":"DEFAULT",false);
        f.jdbc.execute("delete from tbl_collab_notification_outbox");
        assertThat(CollabNotificationIdentity.resolve(f.sql,f.user,n.getId(),false)).isPresent();
        assertThat(CollabPushPresentation.resolve(n,f.sql)).isEmpty();
        f.jdbc.execute("delete from tbl_notification_receipt");assertThat(CollabNotificationIdentity.resolve(f.sql,f.user,n.getId(),false)).isEmpty();
    }
    @Test void receivedRemainsExactHistoryAfterTerminalTransitionAndNoReasonIsInvented() throws Exception {
        register("ANDROID_NATIVE_V9");var n=fixture("APPLICATION_RECEIVED","DEFAULT",false);
        f.jdbc.execute("update tbl_collab_application set status='WITHDRAWN_BY_APPLICANT',status_changed_at=status_changed_at+interval '1 second'");
        plan(n);assertThat(f.store.prepare(f.store.claimNext().orElseThrow())).isPresent();
        assertThat(CollabNotificationIdentity.resolve(f.sql,f.user,n.getId(),false).orElseThrow().notification().payload())
            .containsEntry("applicationId",application.toString()).doesNotContainKeys("reason","jobId");
    }
    @ParameterizedTest @ValueSource(strings={"MUSICIAN","VENUE","STUDIO","BAND"})
    void currentActorOwnershipNeverConfusesActorWithUser(String profile) throws Exception {
        register("ANDROID_NATIVE_V9");var n=fixture("APPLICATION_RECEIVED","DEFAULT",false);
        if(!profile.equals("MUSICIAN")) {
            UUID source=UUID.randomUUID();
            if(profile.equals("BAND")) f.jdbc.update("insert into tbl_band_member values(?,?,'ACTIVE','FOUNDER')",source,owner);
            else {
                f.jdbc.execute("delete from tbl_musician_profile where user_id='"+owner+"'");
                String table=profile.equals("VENUE")?"tbl_venues":"tbl_studio_profile";
                if(profile.equals("VENUE")) f.jdbc.update("insert into "+table+" values(?,?,'ACTIVE')",source,owner);
                else f.jdbc.update("insert into "+table+" values(?,?)",source,owner);
                var nextRole=UUID.randomUUID();f.jdbc.update("insert into tbl_role values(?,?)",nextRole,"ROLE_"+profile);f.jdbc.update("update user_roles set role_id=? where user_id=?",nextRole,owner);
            }
            f.jdbc.update("update tbl_collab_actor set profile_type=?,source_profile_id=? where id=?",profile,source,publisherActor);
        }
        plan(n);assertThat(f.store.prepare(f.store.claimNext().orElseThrow())).isPresent();
        if(profile.equals("BAND")) f.jdbc.execute("update tbl_band_member set band_role='MEMBER'");
        else f.jdbc.execute("update tbl_collab_actor set source_profile_id=id");
        assertThat(CollabPushPresentation.resolve(n,f.sql)).isEmpty();
    }
    @Test void forwardIdempotentMigrationAndReadinessKeepOldDevices() throws Exception {
        register("ANDROID_NATIVE_V8");var before=f.jdbc.queryForList("select * from tbl_push_device");var markers=f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id");
        migration();migration();
        assertThat(f.jdbc.queryForList("select * from tbl_push_device")).isEqualTo(before);
        assertThat(f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id")).isEqualTo(markers);
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-overthinking-capability");
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-10-01-push-native-overthinking-capability.sql")));
        f.operations.run(new DefaultApplicationArguments());
        assertThat(f.jdbc.queryForList("select * from tbl_push_device")).isEqualTo(before);
        f.jdbc.execute("delete from soundconnect_schema_migrations where migration_id='2026-10-01-push-native-collab-capability'");
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-collab-capability");
    }
}
