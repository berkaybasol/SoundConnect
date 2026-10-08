package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.*;
import com.berkayb.soundconnect.modules.notification.support.BandNotificationIdentity;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="push.test.jdbc-url",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/soundconnect_push_test")
class BandPushPostgresTest {
    final PushFoundationPostgresTest f=new PushFoundationPostgresTest();
    UUID band, invitation;
    @BeforeEach void setup() throws Exception {
        f.setup(); f.properties.setMaxDevicesPerUser(30); f.properties.setAllowedTypes(BandPushPresentation.TYPES);
        f.jdbc.execute("create table tbl_role(id uuid primary key,name text)");
        f.jdbc.execute("create table user_roles(user_id uuid,role_id uuid)");
        f.jdbc.execute("create table \"tbl_listener-profile\"(id uuid primary key,user_id uuid)");
        f.jdbc.execute("create table tbl_musician_profile(id uuid primary key,user_id uuid)");
        f.jdbc.execute("create table tbl_band(id uuid primary key)");
        f.jdbc.execute("create table tbl_band_member(id uuid primary key,band_id uuid,user_id uuid,band_role text,status text,invitation_id uuid)");
        band=UUID.randomUUID(); invitation=UUID.randomUUID();
        f.jdbc.update("insert into tbl_band values(?)",band);
        var role=UUID.randomUUID(); f.jdbc.update("insert into tbl_role values(?,'ROLE_MUSICIAN')",role);
        for(var user:List.of(f.user,f.other)) {
            f.jdbc.update("insert into user_roles values(?,?)",user,role);
            f.jdbc.update("insert into tbl_musician_profile values(?,?)",UUID.randomUUID(),user);
        }
        ReflectionTestUtils.setField(f.store,"policy",new NotificationDeliveryPolicy(new AccountDeliveryFence(f.sql),f.sql,f.transactionManager,mock(AfterCommitDeliveryExecutor.class)));
    }
    @AfterEach void cleanup(){ f.cleanup(); }
    UUID register(String capability) {
        var id=UUID.randomUUID();register(id,capability,1); return id;
    }
    void register(UUID id,String capability,long revision) {
        f.tx.executeWithoutResult(s->f.devices.register(f.user,id,new PushDeviceService.Registration("band-fixture-"+id,PushDeviceService.Platform.ANDROID,PushDeviceService.Permission.AUTHORIZED,"band-test",revision,capability)));
    }
    Notification notification(NotificationType type) {
        f.jdbc.update("delete from tbl_band_member");
        boolean toMember=type==NotificationType.BAND_INVITE_RECEIVED || type==NotificationType.BAND_MEMBER_REMOVED;
        String status=switch(type) {case BAND_INVITE_RECEIVED -> "PENDING";case BAND_INVITE_ACCEPTED -> "ACTIVE";case BAND_INVITE_REJECTED -> "REJECTED";default -> "LEFT";};
        f.jdbc.update("insert into tbl_band_member values(?,?,?,'FOUNDER','ACTIVE',null)",UUID.randomUUID(),band,toMember?f.other:f.user);
        f.jdbc.update("insert into tbl_band_member values(?,?,?,'MEMBER',?,?)",UUID.randomUUID(),band,toMember?f.user:f.other,status,invitation);
        var payload=new HashMap<String,Object>(Map.of("module","BAND","bandId",band.toString(),"bandIdentityVersion",1,"action",BandNotificationIdentity.action(type),BandNotificationIdentity.actorKey(type),f.other.toString()));
        if(type.name().startsWith("BAND_INVITE_"))payload.put("invitationId",invitation.toString());
        var n=Notification.builder().recipientId(f.user).type(type).sourceEventId(UUID.randomUUID()).occurredAt(f.clock.instant()).title("PRIVATE BAND SNAPSHOT").message("PRIVATE ACTOR").payload(payload).read(false).build();
        ReflectionTestUtils.setField(n,"id",UUID.randomUUID()); f.jdbc.update("insert into tbl_notification(id,recipient_id) values(?,?)",n.getId(),f.user);f.inbox.put(n.getId(),n);return n;
    }
    void plan(Notification n){f.tx.executeWithoutResult(s->f.planner.plan(new NotificationPersisted(n)));}
    @ParameterizedTest @EnumSource(value=NotificationType.class,names={"BAND_INVITE_RECEIVED","BAND_INVITE_ACCEPTED","BAND_INVITE_REJECTED","BAND_MEMBER_REMOVED","BAND_MEMBER_LEFT"})
    void fiveRealSourceShapesOnlyV7AndDeduplicatedClosedWire(NotificationType type) {
        for(String v:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3","ANDROID_NATIVE_V4","ANDROID_NATIVE_V5","ANDROID_NATIVE_V6"))register(v);
        register(null);var id=register(BandPushPresentation.CAPABILITY);
        var n=notification(type);plan(n);plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(1);
        var claim=f.store.claimNext().orElseThrow();assertThat(claim.installationId()).isEqualTo(id);
        var wire=f.store.prepare(claim).orElseThrow().data();
        assertThat(wire).containsOnlyKeys("notificationId","recipientId","type","presentationVersion","displayVariant","sentAt","expiresAt")
            .containsEntry("notificationId",n.getId().toString()).containsEntry("recipientId",f.user.toString()).containsEntry("type",type.name()).containsEntry("presentationVersion",BandPushPresentation.VERSION);
        assertThat(wire.toString()).doesNotContain("PRIVATE",band.toString(),invitation.toString(),f.other.toString());
    }
    @ParameterizedTest @ValueSource(strings={"legacy","actor","extra","missingInvite","wrongInvite","action","module","band","self","recipient","recipientProfile","actorProfile","founder","state","sourceEvent"})
    void invalidIdentityAndForeignOrStaleSourceNeverPlan(String mutation) {
        register(BandPushPresentation.CAPABILITY);var n=notification(NotificationType.BAND_INVITE_RECEIVED);
        mutate(n,mutation);plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
    }
    void mutate(Notification n,String mutation) {
        switch(mutation) {
            case "legacy" -> n.getPayload().put("bandIdentityVersion",0);
            case "actor" -> n.getPayload().put("inviterId","1-1-1-1-1");
            case "extra" -> n.getPayload().put("bandName","PRIVATE");
            case "missingInvite" -> n.getPayload().remove("invitationId");
            case "wrongInvite" -> n.getPayload().put("invitationId",UUID.randomUUID().toString());
            case "action" -> n.getPayload().put("action","INVITE_ACCEPTED");
            case "module" -> n.getPayload().put("module","SOCIAL");
            case "band" -> n.getPayload().put("bandId",UUID.randomUUID().toString());
            case "self" -> n.getPayload().put("inviterId",f.user.toString());
            case "recipient" -> ReflectionTestUtils.setField(n,"recipientId",f.other);
            case "recipientProfile" -> f.jdbc.update("delete from tbl_musician_profile where user_id=?",f.user);
            case "actorProfile" -> f.jdbc.update("delete from tbl_musician_profile where user_id=?",f.other);
            case "founder" -> f.jdbc.update("update tbl_band_member set band_role='MEMBER' where user_id=?",f.other);
            case "state" -> f.jdbc.update("update tbl_band_member set status='LEFT' where user_id=?",f.user);
            case "sourceEvent" -> ReflectionTestUtils.setField(n,"sourceEventId",null);
        }
    }
    @ParameterizedTest @ValueSource(strings={"legacy","wrongInvite","actorProfile","state","downgrade","revoked","generation","read","owner","recipientErased","actorErased","preference","category","permission","scoped","expired","listener"})
    void deliveryRevalidatesChangesAfterPlanning(String mutation) {
        var device=register(BandPushPresentation.CAPABILITY);var n=notification(NotificationType.BAND_INVITE_RECEIVED);plan(n);var claim=f.store.claimNext().orElseThrow();
        switch(mutation) {
            case "downgrade" -> register(device,"ANDROID_NATIVE_V6",2);
            case "revoked" -> f.tx.executeWithoutResult(s->f.devices.revoke(f.user,device,2L));
            case "generation" -> f.jdbc.update("update tbl_push_device set generation=generation+1");
            case "read" -> f.jdbc.update("update tbl_notification set is_read=true");
            case "owner" -> f.jdbc.update("update tbl_push_device set user_id=?",f.other);
            case "recipientErased" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",f.user);
            case "actorErased" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",f.other);
            case "preference" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(false,Set.of())));
            case "category" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(true,Set.of(n.getType().getCategory()))));
            case "permission" -> f.jdbc.update("update tbl_push_device set permission='DENIED'");
            case "scoped" -> f.jdbc.update("update tbl_push_device set application_scope_id=?",UUID.randomUUID());
            case "expired" -> claim=new PushDeliveryStore.Claim(claim.id(),claim.notificationId(),claim.recipientId(),claim.installationId(),claim.deviceGeneration(),1,f.clock.instant(),claim.leaseOwner());
            case "listener" -> {var role=UUID.randomUUID();f.jdbc.update("insert into tbl_role values(?,'ROLE_LISTENER')",role);f.jdbc.update("insert into user_roles values(?,?)",f.user,role);}
            default -> mutate(n,mutation);
        }
        assertThat(f.store.prepare(claim)).isEmpty();
    }
    @Test void lateV6RegistrationCannotDowngradeV7AndRollbackDoesNotCommitAJob() {
        var id=register("ANDROID_NATIVE_V6");register(id,BandPushPresentation.CAPABILITY,3);register(id,"ANDROID_NATIVE_V6",2);
        assertThat(f.jdbc.queryForObject("select presentation_version from tbl_push_device",String.class)).isEqualTo(BandPushPresentation.CAPABILITY);
        assertThatThrownBy(()->f.tx.executeWithoutResult(s->{plan(notification(NotificationType.BAND_INVITE_RECEIVED));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
    }
    @Test void forwardReplayPreservesPrior37MarkersDevicesAndReceipts() throws Exception {
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-29-push-native-media-capability.sql")));
        f.jdbc.update("delete from soundconnect_schema_migrations where migration_id='2026-09-29-push-native-band-capability'");
        for(int i=0;i<30;i++)f.jdbc.update("insert into soundconnect_schema_migrations(migration_id) values(?)","fixture-prior-"+i);
        f.jdbc.execute("create table tbl_notification_receipt(source_event_id uuid,recipient_id uuid,recorded_at timestamptz)");
        f.jdbc.update("insert into tbl_notification_receipt values(?,?,now())",UUID.randomUUID(),f.user);
        for(String v:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3","ANDROID_NATIVE_V4","ANDROID_NATIVE_V5","ANDROID_NATIVE_V6"))register(v);
        register(null);
        var devices=f.jdbc.queryForList("select * from tbl_push_device order by installation_id");
        var receipts=f.jdbc.queryForList("select * from tbl_notification_receipt");
        var oldMarkers=f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id");assertThat(oldMarkers).hasSize(37);
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-band");
        var migration=Files.readString(Path.of("scripts/db/2026-09-29-push-native-band-capability.sql"));f.jdbc.execute(migration);
        var markers=f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id");f.jdbc.execute(migration);
        assertThat(markers).hasSize(38).containsAll(oldMarkers);
        assertThat(f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id")).isEqualTo(markers);
        assertThat(f.jdbc.queryForList("select * from tbl_push_device order by installation_id")).isEqualTo(devices);
        assertThat(f.jdbc.queryForList("select * from tbl_notification_receipt")).isEqualTo(receipts);
        assertThatThrownBy(() -> f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-table-capability");register(BandPushPresentation.CAPABILITY);
        assertThatThrownBy(()->f.jdbc.update("update tbl_push_device set presentation_version='ANDROID_NATIVE_V8'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.jdbc.update("update tbl_push_device set platform='IOS'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
