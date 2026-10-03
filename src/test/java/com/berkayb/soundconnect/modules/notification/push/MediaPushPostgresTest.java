package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.*;
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

@EnabledIfSystemProperty(named="push.test.jdbc-url",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/soundconnect_push_test")
class MediaPushPostgresTest {
    final PushFoundationPostgresTest f=new PushFoundationPostgresTest();
    UUID asset;
    @BeforeEach void setup() throws Exception {
        f.setup(); f.properties.setMaxDevicesPerUser(30); f.properties.setAllowedTypes(MediaPushPresentation.TYPES);
        f.jdbc.execute("create table tbl_role(id uuid primary key,name text)");
        f.jdbc.execute("create table user_roles(user_id uuid,role_id uuid)");
        f.jdbc.execute("create table \"tbl_listener-profile\"(id uuid primary key,user_id uuid,visibility_choice_completed boolean,visibility_mode text)");
        f.jdbc.execute("create table tbl_media_asset(id uuid primary key,owner_type text,owner_id uuid,status text,visibility text,content_audience text,playback_url text,source_url text)");
        asset=UUID.randomUUID();
        f.jdbc.update("insert into tbl_media_asset values(?,'USER',?,'READY','PUBLIC','MAINSTAGE',null,'https://fixture.invalid/secret')",asset,f.user);
        ReflectionTestUtils.setField(f.store,"policy",new NotificationDeliveryPolicy(new AccountDeliveryFence(f.sql),f.sql,f.transactionManager,mock(AfterCommitDeliveryExecutor.class)));
    }
    @AfterEach void cleanup(){ f.cleanup(); }
    UUID register(String capability) {
        var id=UUID.randomUUID(); register(id,capability,1); return id;
    }
    void register(UUID id,String capability,long revision) {
        f.tx.executeWithoutResult(s->f.devices.register(f.user,id,new PushDeviceService.Registration("fixture-"+id,PushDeviceService.Platform.ANDROID,PushDeviceService.Permission.AUTHORIZED,"media-test",revision,capability)));
    }
    Notification notification(boolean comment) {
        var payload=new HashMap<String,Object>(Map.of("module","SOCIAL","targetType","MEDIA","targetId",asset.toString(),"actorId",f.other.toString(),"mediaIdentityVersion",1));
        if(comment) payload.put("commentId",UUID.randomUUID().toString());
        var n=Notification.builder().recipientId(f.user).type(comment?NotificationType.SOCIAL_COMMENT:NotificationType.SOCIAL_LIKE)
                .sourceEventId(UUID.randomUUID()).occurredAt(f.clock.instant()).title("Private snapshot").message("Private comment").payload(payload).read(false).build();
        ReflectionTestUtils.setField(n,"id",UUID.randomUUID());f.jdbc.update("insert into tbl_notification(id,recipient_id) values(?,?)",n.getId(),f.user);f.inbox.put(n.getId(),n);return n;
    }
    void plan(Notification n){ f.tx.executeWithoutResult(s->f.planner.plan(new NotificationPersisted(n))); }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void onlyV6PlansDeduplicatedIdentityFreeWire(boolean comment) {
        for(String v:List.of("ANDROID_DM_V1","ANDROID_NATIVE_V2","ANDROID_NATIVE_V3","ANDROID_NATIVE_V4","ANDROID_NATIVE_V5")) register(v);
        register(null);var current=register(MediaPushPresentation.CAPABILITY);var n=notification(comment);plan(n);plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(1);
        var job=f.store.claimNext().orElseThrow();assertThat(job.installationId()).isEqualTo(current);
        var data=f.store.prepare(job).orElseThrow().data();
        assertThat(data).containsOnlyKeys("notificationId","recipientId","type","presentationVersion","displayVariant","sentAt","expiresAt")
                .containsEntry("presentationVersion",MediaPushPresentation.VERSION).containsEntry("type",n.getType().name());
        assertThat(data.toString()).doesNotContain("Private",asset.toString(),f.other.toString(),"https");
    }
    @Test void revisionFencesLateRegistrationAndPrepareRechecksDowngradeWithoutInventingGeneration() {
        var id=register("ANDROID_NATIVE_V5");register(id,MediaPushPresentation.CAPABILITY,3);
        long generation=f.jdbc.queryForObject("select generation from tbl_push_device where installation_id=?",Long.class,id);
        register(id,"ANDROID_NATIVE_V5",2);
        assertThat(f.jdbc.queryForObject("select presentation_version from tbl_push_device where installation_id=?",String.class,id)).isEqualTo(MediaPushPresentation.CAPABILITY);
        var n=notification(false);plan(n);var job=f.store.claimNext().orElseThrow();
        register(id,"ANDROID_NATIVE_V5",4);
        assertThat(f.jdbc.queryForObject("select generation from tbl_push_device where installation_id=?",Long.class,id)).isEqualTo(generation);
        assertThat(f.store.prepare(job)).isEmpty();
        assertThat(f.jdbc.queryForObject("select last_error_code from tbl_push_delivery",String.class)).isEqualTo("PRESENTATION_UNAVAILABLE");
    }
    @ParameterizedTest @ValueSource(strings={"hidden","missing","notReady","noUrl","wrongTarget","scope","actorErased","recipientErased","permission","generation","read","preference","category","ttl","allowlist","ghostOwner","listenerAudience"})
    void prepareRejectsFreshChanges(String change) {
        var device=register(MediaPushPresentation.CAPABILITY);var n=notification(true);plan(n);var job=f.store.claimNext().orElseThrow();
        switch(change) {
            case "hidden" -> f.jdbc.update("update tbl_media_asset set visibility='PRIVATE'");
            case "missing" -> f.jdbc.update("delete from tbl_media_asset");
            case "notReady" -> f.jdbc.update("update tbl_media_asset set status='PROCESSING'");
            case "noUrl" -> f.jdbc.update("update tbl_media_asset set source_url=null");
            case "wrongTarget" -> n.getPayload().put("targetType","EVENT");
            case "scope" -> f.jdbc.update("update tbl_push_device set application_scope_id=?",UUID.randomUUID());
            case "actorErased" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",f.other);
            case "recipientErased" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",f.user);
            case "permission" -> f.jdbc.update("update tbl_push_device set permission='DENIED'");
            case "generation" -> f.jdbc.update("update tbl_push_device set generation=generation+1");
            case "read" -> f.jdbc.update("update tbl_notification set is_read=true");
            case "preference" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(false,Set.of())));
            case "category" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(true,Set.of("SOCIAL"))));
            case "ttl" -> job=new PushDeliveryStore.Claim(job.id(),job.notificationId(),job.recipientId(),device,job.deviceGeneration(),1,f.clock.instant(),job.leaseOwner());
            case "allowlist" -> f.properties.setAllowedTypes(Set.of(NotificationType.DM_NEW_MESSAGE));
            case "ghostOwner" -> f.jdbc.update("insert into \"tbl_listener-profile\" values(?,?,true,'GHOST')",UUID.randomUUID(),f.user);
            case "listenerAudience" -> {
                var role=UUID.randomUUID(); f.jdbc.update("insert into tbl_role values(?,'ROLE_LISTENER')",role);f.jdbc.update("insert into user_roles values(?,?)",f.user,role);
                f.jdbc.update("update tbl_media_asset set content_audience='BACKSTAGE'");
            }
        }
        assertThat(f.store.prepare(job)).isEmpty();
    }
    @Test void v6PlansAllPriorSeventeenAndOnlyTwoScopedApplicationTypes() {
        var general=register(MediaPushPresentation.CAPABILITY);var scoped=register(MediaPushPresentation.CAPABILITY);var app=UUID.randomUUID();
        f.jdbc.update("update tbl_push_device set application_scope_id=? where installation_id=?",app,scoped);
        var types=EnumSet.copyOf(VenuePushPresentation.TYPES);types.addAll(VenueApplicationPushPresentation.TYPES);types.addAll(StudioPushPresentation.TYPES);types.addAll(FollowPushPresentation.TYPES);types.add(NotificationType.DM_NEW_MESSAGE);
        f.properties.setAllowedTypes(types);assertThat(types).hasSize(17);
        for(var type:types){var n=notification(false);ReflectionTestUtils.setField(n,"type",type);n.getPayload().put("applicationId",app.toString());plan(n);}
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery where installation_id=?",Integer.class,general)).isEqualTo(17);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery where installation_id=?",Integer.class,scoped)).isEqualTo(2);
    }
    @Test void forwardFromV5AndReplayPreserveDevicesAndEarlierMarkers() throws Exception {
        // Explicit old schema fixture; no V6 rows exist when installing its predecessor.
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-28-push-native-follow-capability.sql")));
        f.jdbc.update("delete from soundconnect_schema_migrations where migration_id='2026-09-29-push-native-media-capability'");
        register("ANDROID_NATIVE_V5"); var before=f.jdbc.queryForList("select * from tbl_push_device order by installation_id");
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-media");
        var migration=Files.readString(Path.of("scripts/db/2026-09-29-push-native-media-capability.sql"));f.jdbc.execute(migration);
        var markers=f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id");f.jdbc.execute(migration);
        assertThat(f.jdbc.queryForList("select * from tbl_push_device order by installation_id")).isEqualTo(before);
        assertThat(f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id")).isEqualTo(markers);
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-29-push-native-band-capability.sql")));
        assertThatThrownBy(() -> f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-table-capability");register(MediaPushPresentation.CAPABILITY);
        assertThatThrownBy(()->f.jdbc.update("update tbl_push_device set presentation_version='ANDROID_NATIVE_V8'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.jdbc.update("update tbl_push_device set platform='IOS'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
