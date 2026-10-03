package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real disposable PostgreSQL registration/planning/claims and account fence.
 * Notification lookup fixture is an in-memory repository; end-to-end production
 * follow/inbox is covered separately in FollowNotificationOutboxPostgresRabbitIT. */
@EnabledIfSystemProperty(named="push.test.jdbc-url",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/soundconnect_push_test")
class FollowPushPostgresTest {
    final PushFoundationPostgresTest f=new PushFoundationPostgresTest();
    UUID band;
    @BeforeEach void setup() throws Exception {
        f.setup(); f.properties.setMaxDevicesPerUser(30); f.properties.setAllowedTypes(FollowPushPresentation.TYPES);
        f.jdbc.execute("create table tbl_band(id uuid primary key)");band=UUID.randomUUID();f.jdbc.update("insert into tbl_band values(?)",band);
        ReflectionTestUtils.setField(f.store,"policy",new NotificationDeliveryPolicy(new AccountDeliveryFence(f.sql),f.sql,f.transactionManager,mock(AfterCommitDeliveryExecutor.class)));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void v6PreparesBothFollowTypes(boolean forBand) {
        register(MediaPushPresentation.CAPABILITY,PushDeviceService.Platform.ANDROID);
        var n=notification(forBand);plan(n);
        assertThat(f.store.prepare(claim()).orElseThrow().data()).containsEntry("presentationVersion",FollowPushPresentation.VERSION)
                .containsEntry("notificationId",n.getId().toString());
    }

    @AfterEach void cleanup(){f.cleanup();}
    UUID register(String version,PushDeviceService.Platform platform) {
        UUID id=UUID.randomUUID(); f.tx.executeWithoutResult(s->f.devices.register(f.user,id,new PushDeviceService.Registration("fixture-"+id,platform,PushDeviceService.Permission.AUTHORIZED,"test",1L,version)));return id;
    }
    Notification notification(boolean forBand){
        var p=new HashMap<String,Object>();p.put("action",forBand?"NEW_BAND_FOLLOWER":"NEW_FOLLOWER");p.put("followerId",f.other.toString());p.put("followerUsername","Private old identity");p.put("followerAvatarUrl","https://private.invalid/avatar");if(forBand)p.put("bandId",band.toString());
        var n=Notification.builder().recipientId(f.user).type(forBand?NotificationType.SOCIAL_NEW_BAND_FOLLOWER:NotificationType.SOCIAL_NEW_FOLLOWER).sourceEventId(UUID.randomUUID()).occurredAt(f.clock.instant()).title("Private name").message("Private body").payload(p).read(false).build();
        ReflectionTestUtils.setField(n,"id",UUID.randomUUID()); f.jdbc.update("insert into tbl_notification(id,recipient_id) values(?,?)",n.getId(),f.user);f.inbox.put(n.getId(),n);return n;
    }
    void plan(Notification n){f.tx.executeWithoutResult(s->f.planner.plan(new NotificationPersisted(n)));}
    PushDeliveryStore.Claim claim(){return f.store.claimNext().orElseThrow();}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void onlyV5PlansOnceAndPreparesIdentityFreeExactWire(boolean forBand){
        for(String v:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3","ANDROID_NATIVE_V4"))register(v,PushDeviceService.Platform.ANDROID);
        register(null,PushDeviceService.Platform.ANDROID);register(null,PushDeviceService.Platform.IOS);
        var current=register(FollowPushPresentation.CAPABILITY,PushDeviceService.Platform.ANDROID);var n=notification(forBand);plan(n);plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(1);
        var job=claim();assertThat(job.installationId()).isEqualTo(current);
        var data=f.store.prepare(job).orElseThrow().data();
        assertThat(data).containsOnlyKeys("notificationId","recipientId","type","presentationVersion","displayVariant","sentAt","expiresAt").containsEntry("notificationId",n.getId().toString()).containsEntry("presentationVersion",FollowPushPresentation.VERSION);
        assertThat(data.toString()).doesNotContain("Private",f.other.toString(),band.toString(),"avatar");
    }
    @ParameterizedTest @ValueSource(strings={"downgrade","permission","revoked","generation","scope","read","deleted","account","actor","band","preference","category","flag","allowlist","ttl"})
    void freshPrepareRejectsChangedEligibility(String change){
        var device=register(FollowPushPresentation.CAPABILITY,PushDeviceService.Platform.ANDROID);var n=notification(true);plan(n);var job=claim();
        switch(change){
            case "downgrade" -> f.jdbc.update("update tbl_push_device set presentation_version='ANDROID_NATIVE_V4'");
            case "permission" -> f.jdbc.update("update tbl_push_device set permission='DENIED'");
            case "revoked" -> f.tx.executeWithoutResult(s->f.devices.revoke(f.user,device,2L));
            case "generation" -> f.jdbc.update("update tbl_push_device set generation=generation+1");
            case "scope" -> f.jdbc.update("update tbl_push_device set application_scope_id=?",UUID.randomUUID());
            case "read" -> f.jdbc.update("update tbl_notification set is_read=true");
            case "deleted" -> f.inbox.remove(n.getId());
            case "account" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",f.user);
            case "actor" -> f.jdbc.update("delete from tbl_user where id=?",f.other);
            case "band" -> f.jdbc.update("delete from tbl_band where id=?",band);
            case "preference" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(false,Set.of())));
            case "category" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(true,Set.of("SOCIAL"))));
            case "flag" -> f.properties.setEnabled(false);
            case "allowlist" -> f.properties.setAllowedTypes(Set.of(NotificationType.DM_NEW_MESSAGE));
            case "ttl" -> job=new PushDeliveryStore.Claim(job.id(),job.notificationId(),job.recipientId(),job.installationId(),job.deviceGeneration(),job.attempts(),f.clock.instant(),job.leaseOwner());
        }
        assertThat(f.store.prepare(job)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"permission","revoked","scope","preference","category","flag","ttl","read"})
    void plannerDoesNotAdmitIneligibleFollow(String change){
        var device=register(FollowPushPresentation.CAPABILITY,PushDeviceService.Platform.ANDROID);var n=notification(false);
        switch(change){
            case "permission" -> f.jdbc.update("update tbl_push_device set permission='DENIED'");
            case "revoked" -> f.tx.executeWithoutResult(s->f.devices.revoke(f.user,device,2L));
            case "scope" -> f.jdbc.update("update tbl_push_device set application_scope_id=?",UUID.randomUUID());
            case "preference" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(false,Set.of())));
            case "category" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(true,Set.of("SOCIAL"))));
            case "flag" -> f.properties.setEnabled(false);
            case "ttl" -> ReflectionTestUtils.setField(n,"occurredAt",f.clock.instant().minusSeconds(40*86400));
            case "read" -> ReflectionTestUtils.setField(n,"read",true);
        }
        plan(n);assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
    }
    @Test void v5PlansAllFifteenAndKeepsScopedApplicationFence(){
        var general=register(FollowPushPresentation.CAPABILITY,PushDeviceService.Platform.ANDROID);var scoped=register(FollowPushPresentation.CAPABILITY,PushDeviceService.Platform.ANDROID);var application=UUID.randomUUID();
        f.jdbc.update("update tbl_push_device set application_scope_id=? where installation_id=?",application,scoped);
        var types=EnumSet.copyOf(VenuePushPresentation.TYPES);types.addAll(VenueApplicationPushPresentation.TYPES);types.addAll(StudioPushPresentation.TYPES);types.add(NotificationType.DM_NEW_MESSAGE);f.properties.setAllowedTypes(types);
        for(var type:types){var n=notification(false);ReflectionTestUtils.setField(n,"type",type);n.getPayload().put("applicationId",application.toString());plan(n);}
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery where installation_id=?",Integer.class,general)).isEqualTo(15);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery where installation_id=?",Integer.class,scoped)).isEqualTo(2);
    }
    @Test void forwardReplayPreservesAllDeviceFieldsAndMarkerChainAndChecks() throws Exception {
        for(String v:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3","ANDROID_NATIVE_V4","ANDROID_NATIVE_V5"))register(v,PushDeviceService.Platform.ANDROID);
        var before=f.jdbc.queryForList("select * from tbl_push_device order by installation_id");var markers=f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id");
        var sql=Files.readString(Path.of("scripts/db/2026-09-29-push-native-media-capability.sql"));f.jdbc.execute(sql);f.jdbc.execute(sql);
        assertThat(f.jdbc.queryForList("select * from tbl_push_device order by installation_id")).isEqualTo(before);assertThat(f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id")).isEqualTo(markers);f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-29-push-native-band-capability.sql")));assertThatThrownBy(() -> f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-table-capability");
        assertThatThrownBy(()->f.jdbc.update("update tbl_push_device set presentation_version='ANDROID_NATIVE_V8'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        f.jdbc.update("delete from soundconnect_schema_migrations where migration_id='2026-09-24-push-native-studio-capability'");
        assertThatThrownBy(()->f.jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        f.jdbc.execute("rollback");
        assertThat(f.jdbc.queryForList("select * from tbl_push_device order by installation_id")).isEqualTo(before);
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-follow-capability");
    }
}
