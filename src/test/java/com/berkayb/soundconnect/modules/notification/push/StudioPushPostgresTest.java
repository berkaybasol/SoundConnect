package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.push.transport.PushEnvelope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.berkayb.soundconnect.modules.notification.enums.NotificationType.*;

/** Real isolated PG for source locks, capability planning, final payload, migrations and durable retry. */
@EnabledIfSystemProperty(named="push.test.jdbc-url",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/soundconnect_push_test")
class StudioPushPostgresTest {
    private final PushFoundationPostgresTest f=new PushFoundationPostgresTest();
    private UUID owner,customer,studio,room,role,customerRole;
    private StudioPushEligibility eligibility;
    @BeforeEach void setup() throws Exception {
        f.setup(); owner=f.user; customer=f.other; studio=UUID.randomUUID(); room=UUID.randomUUID(); role=UUID.randomUUID();
        f.jdbc.execute("create table tbl_role(id uuid primary key,name text)");
        f.jdbc.execute("create table user_roles(user_id uuid,role_id uuid,primary key(user_id,role_id))");
        f.jdbc.execute("create table \"tbl_listener-profile\"(id uuid primary key,user_id uuid)");
        f.jdbc.execute("create table tbl_studio_profile(id uuid primary key,user_id uuid)");
        f.jdbc.execute("create table tbl_studio_room(id uuid primary key,studio_profile_id uuid,archived_at timestamptz)");
        f.jdbc.execute("""
            create table tbl_studio_room_reservation(id uuid primary key,room_id uuid,requester_id uuid,status text,
                starts_at timestamptz,ends_at timestamptz,approval_required_snapshot boolean,
                decided_at timestamptz,decided_by uuid,cancelled_at timestamptz,cancelled_by uuid)
            """);
        f.jdbc.update("insert into tbl_role values(?,'ROLE_STUDIO')",role);
        f.jdbc.update("insert into user_roles values(?,?)",owner,role);
        customerRole=UUID.randomUUID();f.jdbc.update("insert into tbl_role values(?,'ROLE_MUSICIAN')",customerRole);
        f.jdbc.update("insert into user_roles values(?,?)",customer,customerRole);
        f.jdbc.update("insert into tbl_studio_profile values(?,?)",studio,owner);
        f.jdbc.update("insert into tbl_studio_room values(?,?,null)",room,studio);
        f.properties.setAllowedTypes(EnumSet.copyOf(StudioPushPresentation.TYPES));
        eligibility=new StudioPushEligibility(f.sql,f.clock);
        register(owner,StudioPushPresentation.CAPABILITY,PushDeviceService.Platform.ANDROID);
        register(customer,StudioPushPresentation.CAPABILITY,PushDeviceService.Platform.ANDROID);
    }
    @Test void v6PreparesAllSixStudioTypesAndNineVariants() {
        f.jdbc.update("update tbl_push_device set presentation_version=?",MediaPushPresentation.CAPABILITY);
        allNineVariantsUseSevenFieldsAndNeverExposeSnapshots();
    }

    @AfterEach void cleanup(){f.cleanup();}
    private UUID register(UUID user,String capability,PushDeviceService.Platform platform) {
        UUID installation=UUID.randomUUID();
        f.tx.executeWithoutResult(s->f.devices.register(user,installation,new PushDeviceService.Registration(
                "fixture-"+installation,platform,PushDeviceService.Permission.AUTHORIZED,"studio-test",1L,capability)));
        return installation;
    }
    private Notification snapshot(NotificationType type,String action,String status,boolean approval) {
        UUID id=UUID.randomUUID(); Instant now=f.clock.instant(), starts=now.plusSeconds(3600), ends=now.plusSeconds(7200);
        boolean ownerRecipient=Set.of(STUDIO_RESERVATION_CREATED,STUDIO_RESERVATION_CONFLICTING_REQUESTS,STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER).contains(type);
        Instant decided=Set.of(STUDIO_RESERVATION_APPROVED,STUDIO_RESERVATION_REJECTED).contains(type)?now.minusSeconds(60):null;
        Instant cancelled=Set.of(STUDIO_RESERVATION_CANCELLED_BY_STUDIO,STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER).contains(type)?now.minusSeconds(30):null;
        UUID cancelledBy=cancelled==null?null:type==STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER?customer:owner;
        f.jdbc.update("insert into tbl_studio_room_reservation values(?,?,?,?,?,?,?,?,?,?,?)",id,room,customer,status,
                Timestamp.from(starts),Timestamp.from(ends),approval,decided==null?null:Timestamp.from(decided),decided==null?null:owner,
                cancelled==null?null:Timestamp.from(cancelled),cancelledBy);
        f.jdbc.update("update tbl_studio_room set archived_at=? where id=?",
                action.equals("CANCELLED_BY_STUDIO_ROOM_ARCHIVED")?Timestamp.from(cancelled):null,room);
        var p=new LinkedHashMap<String,Object>();
        p.put("module","STUDIO");p.put("action",action);p.put("status",status);p.put("reservationId",id.toString());
        p.put("roomId",room.toString());p.put("studioProfileId",studio.toString());p.put("requesterId",customer.toString());
        p.put("startsAt",starts.toString());p.put("endsAt",ends.toString());p.put("roomName","Private room");
        return notification(ownerRecipient?owner:customer,type,p);
    }
    private Notification notification(UUID recipient,NotificationType type,Map<String,Object> payload) {
        var n=Notification.builder().sourceEventId(UUID.randomUUID()).recipientId(recipient).type(type).title("Private title")
                .message("Private body with phone").payload(Map.copyOf(payload)).occurredAt(f.clock.instant()).read(false).build();
        ReflectionTestUtils.setField(n,"id",UUID.randomUUID());
        f.jdbc.update("insert into tbl_notification(id,recipient_id) values(?,?)",n.getId(),recipient);f.inbox.put(n.getId(),n);return n;
    }
    private UUID id(Notification n){return UUID.fromString((String)n.getPayload().get("reservationId"));}
    private void plan(Notification n){f.tx.executeWithoutResult(s->f.planner.plan(new NotificationPersisted(n)));}
    private PushDeliveryStore.Claim claim(){return f.store.claimNext().orElseThrow();}
    private Optional<StudioPushPresentation.Variant> eligible(Notification n){return f.tx.execute(s->eligibility.resolve(n));}
    private Notification pending(){return snapshot(STUDIO_RESERVATION_CREATED,"CREATED","PENDING_APPROVAL",true);}
    private static List<String[]> mappings(){return List.of(
        new String[]{"CREATED","CREATED","PENDING_APPROVAL","true","STUDIO_CREATED_PENDING"},
        new String[]{"CREATED","CREATED","CONFIRMED","false","STUDIO_CREATED_CONFIRMED"},
        new String[]{"CONFLICTING_REQUESTS","CONFLICTING_REQUESTS","PENDING_APPROVAL","true","STUDIO_CONFLICTING_REQUESTS"},
        new String[]{"APPROVED","APPROVED","CONFIRMED","true","STUDIO_APPROVED"},
        new String[]{"REJECTED","REJECTED","REJECTED_BY_STUDIO","true","STUDIO_REJECTED"},
        new String[]{"REJECTED","AUTO_REJECTED_CONFLICT","REJECTED_BY_STUDIO","true","STUDIO_REJECTED_CONFLICT"},
        new String[]{"CANCELLED_BY_CUSTOMER","CANCELLED_BY_CUSTOMER","CANCELLED_BY_CUSTOMER","false","STUDIO_CANCELLED_BY_CUSTOMER"},
        new String[]{"CANCELLED_BY_STUDIO","CANCELLED_BY_STUDIO","CANCELLED_BY_STUDIO","false","STUDIO_CANCELLED_BY_STUDIO"},
        new String[]{"CANCELLED_BY_STUDIO","CANCELLED_BY_STUDIO_ROOM_ARCHIVED","CANCELLED_BY_STUDIO","true","STUDIO_ROOM_ARCHIVED"});}

    @Test void allNineVariantsUseSevenFieldsAndNeverExposeSnapshots() {
        for(var m:mappings()) {
            var n=snapshot(NotificationType.valueOf("STUDIO_RESERVATION_"+m[0]),m[1],m[2],Boolean.parseBoolean(m[3]));
            if(n.getType()==STUDIO_RESERVATION_CONFLICTING_REQUESTS) pending();
            plan(n);var data=f.store.prepare(claim()).orElseThrow().data();
            assertThat(data).containsOnlyKeys("notificationId","recipientId","type","presentationVersion","displayVariant","sentAt","expiresAt")
                    .containsEntry("displayVariant",m[4]).containsEntry("presentationVersion",StudioPushPresentation.VERSION);
            assertThat(data.toString()).doesNotContain("Private",room.toString(),studio.toString(),id(n).toString());
        }
    }
    @Test void capabilityPlanningAndFinalDowngradeNeverUseGenericFallback() {
        for(String old:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3")) register(owner,old,PushDeviceService.Platform.ANDROID);
        register(owner,null,PushDeviceService.Platform.ANDROID);register(owner,null,PushDeviceService.Platform.IOS);
        var n=pending();plan(n);plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(1);
        var job=claim();f.jdbc.update("update tbl_push_device set presentation_version='ANDROID_NATIVE_V3' where installation_id=?",job.installationId());
        assertThat(f.store.prepare(job)).isEmpty();
        assertThat(f.jdbc.queryForObject("select last_error_code from tbl_push_delivery",String.class)).isEqualTo("PRESENTATION_UNAVAILABLE");
    }
    @Test void v4StillPlansAllNinePriorNativeTypesAndUsesDmV1() {
        var types=EnumSet.copyOf(VenuePushPresentation.TYPES);types.addAll(VenueApplicationPushPresentation.TYPES);types.add(DM_NEW_MESSAGE);
        f.properties.setAllowedTypes(types);
        for(var type:types) plan(notification(owner,type,Map.of("conversationId",UUID.randomUUID().toString())));
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(9);
        f.jdbc.execute("delete from tbl_push_delivery");
        var dm=notification(owner,DM_NEW_MESSAGE,Map.of("conversationId",UUID.randomUUID().toString()));plan(dm);
        assertThat(f.store.prepare(claim()).orElseThrow().data()).containsEntry("presentationVersion","ANDROID_DM_V1");
    }
    @Test void mismatchedIdentityActionStatusAndTimeFailClosed() {
        var n=pending();assertThat(eligible(n)).isPresent();
        for(var mutation:Map.of("module","OTHER","action","APPROVED","status","CONFIRMED","reservationId",UUID.randomUUID().toString(),
                "roomId",UUID.randomUUID().toString(),"studioProfileId",UUID.randomUUID().toString(),"requesterId",UUID.randomUUID().toString(),
                "startsAt",f.clock.instant().toString()).entrySet()) {
            var p=new HashMap<>(n.getPayload());p.put(mutation.getKey(),mutation.getValue());
            assertThat(eligible(notification(n.getRecipientId(),n.getType(),p))).as(mutation.getKey()).isEmpty();
        }
        assertThat(eligible(notification(customer,n.getType(),n.getPayload()))).isEmpty();
        f.jdbc.update("update tbl_studio_room_reservation set status='CONFIRMED' where id=?",id(n));assertThat(eligible(n)).isEmpty();
    }
    @Test void inactiveErasedUnverifiedChangedOwnerRolesAndListenerProfilesSuppress() {
        var n=pending();assertThat(eligible(n)).isPresent();
        f.jdbc.update("delete from user_roles where user_id=?",customer);assertThat(eligible(n)).isEmpty();
        f.jdbc.update("insert into user_roles values(?,?)",customer,customerRole);
        for(UUID user:List.of(owner,customer)) {
            for(String change:List.of("status='INACTIVE'","email_verified=false","erased_at=now()")) {
                f.jdbc.update("update tbl_user set "+change+" where id=?",user);assertThat(eligible(n)).isEmpty();
                f.jdbc.update("update tbl_user set status='ACTIVE',email_verified=true,erased_at=null where id=?",user);
            }
            f.jdbc.update("insert into \"tbl_listener-profile\" values(?,?)",UUID.randomUUID(),user);assertThat(eligible(n)).isEmpty();
            f.jdbc.update("delete from \"tbl_listener-profile\" where user_id=?",user);
        }
        f.jdbc.update("delete from user_roles where user_id=?",owner);assertThat(eligible(n)).isEmpty();
        f.jdbc.update("insert into user_roles values(?,?)",owner,role);
        UUID listener=UUID.randomUUID();f.jdbc.update("insert into tbl_role values(?,'ROLE_LISTENER')",listener);
        f.jdbc.update("insert into user_roles values(?,?)",customer,listener);assertThat(eligible(n)).isEmpty();
    }
    @Test void conflictRequiresTwoCurrentFuturePendingRequestsAndActiveRoom() {
        var n=snapshot(STUDIO_RESERVATION_CONFLICTING_REQUESTS,"CONFLICTING_REQUESTS","PENDING_APPROVAL",true);
        assertThat(eligible(n)).isEmpty();var second=pending();assertThat(eligible(n)).isPresent();
        f.jdbc.update("update tbl_studio_room_reservation set status='REJECTED_BY_STUDIO' where id=?",id(second));assertThat(eligible(n)).isEmpty();
        f.jdbc.update("update tbl_studio_room_reservation set status='PENDING_APPROVAL' where id=?",id(second));
        f.jdbc.update("update tbl_studio_room set archived_at=now() where id=?",room);assertThat(eligible(n)).isEmpty();
    }
    @Test void decisionActorsAndArchiveTimeAreFreshWhileTerminalHistoryIsRetained() {
        var approved=snapshot(STUDIO_RESERVATION_APPROVED,"APPROVED","CONFIRMED",true);assertThat(eligible(approved)).isPresent();
        f.jdbc.update("update tbl_studio_room_reservation set decided_by=? where id=?",customer,id(approved));assertThat(eligible(approved)).isEmpty();
        var archive=snapshot(STUDIO_RESERVATION_CANCELLED_BY_STUDIO,"CANCELLED_BY_STUDIO_ROOM_ARCHIVED","CANCELLED_BY_STUDIO",true);
        assertThat(eligible(archive)).isPresent();f.jdbc.update("update tbl_studio_room set archived_at=null where id=?",room);assertThat(eligible(archive)).isEmpty();
        var rejected=snapshot(STUDIO_RESERVATION_REJECTED,"REJECTED","REJECTED_BY_STUDIO",true);
        var later=new StudioPushEligibility(f.sql,Clock.fixed(f.clock.instant().plusSeconds(86400),ZoneOffset.UTC));
        java.util.Optional<StudioPushPresentation.Variant> history = f.tx.execute(s->later.resolve(rejected));
        assertThat(history).isPresent();
        var customerCancellation=snapshot(STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER,"CANCELLED_BY_CUSTOMER","CANCELLED_BY_CUSTOMER",false);
        f.jdbc.update("update tbl_studio_room_reservation set cancelled_by=? where id=?",owner,id(customerCancellation));assertThat(eligible(customerCancellation)).isEmpty();
    }
    @Test void readPreferenceAndSourceChangesAfterPlanningSuppressTheJob() {
        var n=pending();plan(n);var job=claim();f.jdbc.update("update tbl_studio_room_reservation set status='CONFIRMED' where id=?",id(n));
        assertThat(f.store.prepare(job)).isEmpty();
        var read=pending();plan(read);var readJob=claim();f.jdbc.update("update tbl_notification set is_read=true where id=?",read.getId());assertThat(f.store.prepare(readJob)).isEmpty();
        var disabled=pending();plan(disabled);var disabledJob=claim();
        f.tx.executeWithoutResult(s->f.devices.updatePreferences(owner,new PushDeviceService.Preferences(true,Set.of("STUDIO"))));
        assertThat(f.store.prepare(disabledJob)).isEmpty();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"profile","room","reservation","owner","requester","role"})
    void sourceContentionIsBoundedAndRequeuedInsteadOfSuppressed(String resource) throws Exception {
        var n=pending();plan(n);var job=claim();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var writer=executor.submit(()->f.tx.executeWithoutResult(s->{
                String table=switch(resource){case "profile"->"tbl_studio_profile";case "room"->"tbl_studio_room";
                    case "reservation"->"tbl_studio_room_reservation";case "role"->"tbl_role";default->"tbl_user";};
                UUID lockedId=switch(resource){case "profile"->studio;case "room"->room;case "reservation"->id(n);
                    case "role"->role;case "owner"->owner;default->customer;};
                f.jdbc.queryForObject("select id from "+table+" where id=? for update",UUID.class,lockedId);locked.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            }));
            try {
                assertThat(locked.await(3,TimeUnit.SECONDS)).isTrue();
                Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),()->assertThatThrownBy(()->f.store.prepare(job))
                        .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class, failure ->
                                assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,
                                        sqlFailure -> assertThat(sqlFailure.getSQLState()).isEqualTo("55P03"))));
                f.store.transientFailure(job);
                assertThat(f.jdbc.queryForObject("select status from tbl_push_delivery",String.class)).isEqualTo("PENDING");
            } finally{release.countDown();}
            writer.get(3,TimeUnit.SECONDS);
        }
        f.jdbc.update("update tbl_push_delivery set next_attempt_at=?",Timestamp.from(f.clock.instant()));
        assertThat(f.store.prepare(claim())).isPresent();
    }
    @Test void forwardMigrationPreservesOldCapabilitiesAndApplicationScopeAndReadiness() throws Exception {
        for(String old:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3"))register(owner,old,PushDeviceService.Platform.ANDROID);
        var before=f.jdbc.queryForList("select * from tbl_push_device order by installation_id");
        String migration=Files.readString(Path.of("scripts/db/2026-09-24-push-native-studio-capability.sql"));
        f.jdbc.execute(migration);f.jdbc.execute(migration);
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-28-push-native-follow-capability.sql")));
        assertThat(f.jdbc.queryForList("select * from tbl_push_device order by installation_id")).isEqualTo(before);
        assertThatThrownBy(() -> f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-table-capability");
        f.jdbc.update("update tbl_push_device set application_scope_id=? where user_id=? and presentation_version='ANDROID_NATIVE_V4'",UUID.randomUUID(),owner);
        assertThatThrownBy(()->f.jdbc.update("update tbl_push_device set presentation_version='ANDROID_NATIVE_V2' where application_scope_id is not null"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->register(owner,StudioPushPresentation.CAPABILITY,PushDeviceService.Platform.IOS)).isInstanceOf(RuntimeException.class);
        f.jdbc.update("delete from soundconnect_schema_migrations where migration_id='2026-09-24-push-native-studio-capability'");
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-studio-capability");
        f.jdbc.execute(migration);f.jdbc.execute("alter table tbl_push_device drop constraint ck_push_device_presentation");
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-studio-capability");
    }
}