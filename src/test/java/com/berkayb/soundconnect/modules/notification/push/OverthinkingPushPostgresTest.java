package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.support.OverthinkingNotificationIdentity;
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

/** Disposable PostgreSQL only; relational source/plan/send tests, not HTTP/FCM acceptance. */
@Testcontainers
class OverthinkingPushPostgresTest {
    private static final List<String> JDBC_OPT_IN_BEFORE_CLASS=PushFoundationPostgresTest.jdbcOptInSettings();
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.4-alpine")
        .withDatabaseName("soundconnect_push_test").withUsername("push_test").withPassword("isolated-fixture");
    final PushFoundationPostgresTest f=new PushFoundationPostgresTest();
    final ObjectMapper json=new ObjectMapper();
    UUID post,request,author,requester;
    @BeforeEach void setup() throws Exception {
        f.setup("jdbc:postgresql://127.0.0.1:"+DB.getMappedPort(5432)+"/soundconnect_push_test",DB.getUsername(),DB.getPassword());
        f.properties.setMaxDevicesPerUser(30);f.properties.setAllowedTypes(OverthinkingPushPresentation.TYPES);
        f.jdbc.execute("alter table tbl_notification drop constraint legacy_notification_type");
        f.jdbc.execute("alter table tbl_notification add column source_event_id uuid, add column payload jsonb, add column occurred_at timestamptz");
        f.jdbc.execute("create table tbl_notification_receipt(source_event_id uuid,recipient_id uuid)");
        f.jdbc.execute("create table tbl_role(id uuid primary key,name text);create table user_roles(user_id uuid,role_id uuid)");
        f.jdbc.execute("create table tbl_studio_profile(user_id uuid)");
        f.jdbc.execute("create table tbl_tracks(id uuid primary key,media_asset_id uuid)");
        f.jdbc.execute("create table tbl_media_asset(id uuid primary key,content_audience text,owner_type text)");
        f.jdbc.execute("create table tbl_overthinking_post(id uuid primary key,author_id uuid,visibility_type text,musician_track_id uuid,band_track_id uuid)");
        f.jdbc.execute("create table tbl_overthinking_reveal_request(id uuid primary key,post_id uuid,author_id uuid,requester_id uuid,status text)");
        f.jdbc.execute("create table tbl_overthinking_notification_outbox(event_id uuid primary key,recipient_id uuid,notification_type text,payload jsonb,occurred_at timestamptz)");
        f.jdbc.execute("insert into soundconnect_schema_migrations(migration_id) values('2026-09-30-table-notification-target')");
        for(String family:List.of("table","collab","overthinking")) migration(family);
        ReflectionTestUtils.setField(f.store,"policy",new NotificationDeliveryPolicy(new AccountDeliveryFence(f.sql),f.sql,f.transactionManager,mock(AfterCommitDeliveryExecutor.class)));
    }
    @AfterEach void cleanup(){f.cleanup();}
    @Test void containerFixtureDoesNotChangeJvmJdbcOptIn() {
        assertThat(PushFoundationPostgresTest.jdbcOptInSettings().equals(JDBC_OPT_IN_BEFORE_CLASS))
            .as("Container fixture must preserve the process-wide JDBC opt-in settings").isTrue();
    }
    void migration(String family) throws Exception {f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-10-01-push-native-"+family+"-capability.sql")));}
    UUID register(String version){
        var id=UUID.randomUUID();f.tx.executeWithoutResult(s->f.devices.register(f.user,id,
            new PushDeviceService.Registration("reveal-fixture-"+id,PushDeviceService.Platform.ANDROID,PushDeviceService.Permission.AUTHORIZED,"test",1L,version)));
        return id;
    }
    Notification fixture(String action) throws Exception {
        author=action.equals("RECEIVED")?f.user:f.other;requester=action.equals("RECEIVED")?f.other:f.user;
        post=UUID.randomUUID();request=UUID.randomUUID();
        f.jdbc.update("insert into tbl_overthinking_post values(?,?,'ANONYMOUS',null,null)",post,author);
        f.jdbc.update("insert into tbl_overthinking_reveal_request values(?,?,?,?,?)",request,post,author,requester,action.equals("RECEIVED")?"PENDING":action);
        var payload=new HashMap<String,Object>(Map.of("module","OVERTHINKING","action","REVEAL_REQUEST_"+action,
            "postId",post.toString(),"revealRequestId",request.toString(),"postTitle","Private anonymous title"));
        if(action.equals("RECEIVED"))payload.put("requesterId",requester.toString());
        if(action.equals("APPROVED"))payload.put("authorId",author.toString());
        var n=Notification.builder().sourceEventId(UUID.randomUUID()).recipientId(f.user)
            .type(NotificationType.valueOf("OVERTHINKING_REVEAL_REQUEST_"+action)).title("Private").message("Private")
            .payload(payload).occurredAt(f.clock.instant()).read(false).build();
        ReflectionTestUtils.setField(n,"id",UUID.randomUUID());
        var now=Timestamp.from(f.clock.instant());
        f.jdbc.update("insert into tbl_notification(id,recipient_id,type,source_event_id,payload,occurred_at) values(?,?,?,?,?::jsonb,?)",
            n.getId(),f.user,n.getType().name(),n.getSourceEventId(),json.writeValueAsString(payload),now);
        f.jdbc.update("insert into tbl_overthinking_notification_outbox values(?,?,?,?::jsonb,?)",n.getSourceEventId(),f.user,n.getType().name(),json.writeValueAsString(payload),now);
        f.jdbc.update("insert into tbl_notification_receipt values(?,?)",n.getSourceEventId(),f.user);
        f.inbox.put(n.getId(),n);return n;
    }
    void plan(Notification n){f.tx.executeWithoutResult(s->f.planner.plan(new NotificationPersisted(n)));}
    Optional<OverthinkingNotificationIdentity.Owned> target(Notification n){return OverthinkingNotificationIdentity.resolve(f.sql,f.user,n.getId(),false);}
    @ParameterizedTest @ValueSource(strings={"RECEIVED","APPROVED","REJECTED"})
    void allTypesPlanOnlyV10IdempotentlyAndPrepareSixPrivateFreeFields(String action) throws Exception {
        var v10=register("ANDROID_NATIVE_V10");register("ANDROID_NATIVE_V9");register("ANDROID_NATIVE_V8");register(null);
        var n=fixture(action);plan(n);plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isOne();
        var claim=f.store.claimNext().orElseThrow();assertThat(claim.installationId()).isEqualTo(v10);
        var wire=f.store.prepare(claim).orElseThrow().data();
        assertThat(wire).containsOnlyKeys("notificationId","recipientId","type","presentationVersion","sentAt","expiresAt")
            .containsEntry("presentationVersion","ANDROID_OVERTHINKING_V1").containsEntry("notificationId",n.getId().toString());
        assertThat(wire.toString()).doesNotContain("Private",post.toString(),request.toString(),action.equals("RECEIVED")?requester.toString():author.toString());
        var owned=target(n).orElseThrow();
        assertThat(owned.notification().payload()).containsEntry("revealRequestId",request.toString()).containsEntry("postId",post.toString())
            .containsOnlyKeys("module","action","postId","revealRequestId","requestStatus","sourceEventId","identityVersion");
        assertThat(OverthinkingNotificationIdentity.resolve(f.sql,f.other,n.getId(),false)).isEmpty();
        assertThat(f.jdbc.queryForObject("select is_read from tbl_notification where id=?",Boolean.class,n.getId())).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"APPROVED","REJECTED"})
    void historicalReceivedSurvivesDecisionAndOutboxRetentionButNeverPlansNewPush(String decision) throws Exception {
        register("ANDROID_NATIVE_V10");var n=fixture("RECEIVED");
        f.jdbc.update("update tbl_overthinking_reveal_request set status=? where id=?",decision,request);
        assertThat(target(n)).isPresent();plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
        f.jdbc.update("delete from tbl_overthinking_notification_outbox where event_id=?",n.getSourceEventId());
        assertThat(target(n).orElseThrow().hasSource()).isFalse();plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"RECEIVED","APPROVED","REJECTED"})
    void retainedExactInboxStillOpensAndCancelledOldCycleNeverBindsToNewRequest(String action) throws Exception {
        var n=fixture(action);f.jdbc.update("delete from tbl_overthinking_notification_outbox where event_id=?",n.getSourceEventId());
        assertThat(target(n)).isPresent();
        f.jdbc.update("delete from tbl_overthinking_reveal_request where id=?",request);
        f.jdbc.update("insert into tbl_overthinking_reveal_request values(?,?,?,?,?)",UUID.randomUUID(),post,author,requester,"PENDING");
        assertThat(target(n)).isEmpty();
    }
    void mutate(String change,Notification n) {
        switch(change) {
            case "request" -> f.jdbc.update("delete from tbl_overthinking_reveal_request where id=?",request);
            case "post" -> f.jdbc.update("delete from tbl_overthinking_post where id=?",post);
            case "postBinding" -> f.jdbc.update("update tbl_overthinking_reveal_request set post_id=? where id=?",UUID.randomUUID(),request);
            case "authorBinding" -> f.jdbc.update("update tbl_overthinking_reveal_request set author_id=requester_id where id=?",request);
            case "decision" -> f.jdbc.update("update tbl_overthinking_reveal_request set status='REJECTED' where id=?",request);
            case "erasure" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",requester);
            case "inactive" -> f.jdbc.update("update tbl_user set status='SUSPENDED' where id=?",author);
            case "unverified" -> f.jdbc.update("update tbl_user set email_verified=false where id=?",requester);
            case "receipt" -> f.jdbc.update("delete from tbl_notification_receipt where source_event_id=?",n.getSourceEventId());
            case "source" -> f.jdbc.update("delete from tbl_overthinking_notification_outbox where event_id=?",n.getSourceEventId());
            case "sourceRecipient" -> f.jdbc.update("update tbl_overthinking_notification_outbox set recipient_id=? where event_id=?",f.other,n.getSourceEventId());
            case "sourceType" -> f.jdbc.update("update tbl_overthinking_notification_outbox set notification_type='OVERTHINKING_REVEAL_REQUEST_APPROVED' where event_id=?",n.getSourceEventId());
            case "sourceTime" -> f.jdbc.update("update tbl_overthinking_notification_outbox set occurred_at=occurred_at+interval '1 second' where event_id=?",n.getSourceEventId());
            case "sourcePayload" -> f.jdbc.update("update tbl_overthinking_notification_outbox set payload=payload || '{\"postTitle\":\"other\"}' where event_id=?",n.getSourceEventId());
            case "read" -> f.jdbc.update("update tbl_notification set is_read=true where id=?",n.getId());
            case "capability" -> f.jdbc.execute("update tbl_push_device set presentation_version='ANDROID_NATIVE_V9'");
            case "permission" -> f.jdbc.execute("update tbl_push_device set permission='DENIED'");
            case "session" -> f.jdbc.update("update tbl_push_device set user_id=?,generation=generation+1",f.other);
            default -> throw new AssertionError(change);
        }
    }
    @ParameterizedTest @ValueSource(strings={"request","post","postBinding","authorBinding","decision","erasure","inactive","unverified","receipt","source","sourceRecipient","sourceType","sourceTime","sourcePayload"})
    void invalidSourcesNeverPlan(String change) throws Exception {
        register("ANDROID_NATIVE_V10");var n=fixture("RECEIVED");mutate(change,n);plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"request","post","postBinding","authorBinding","decision","erasure","inactive","unverified","receipt","source","sourceRecipient","sourceType","sourceTime","sourcePayload","read","capability","permission","session"})
    void planToSendChangesAreSuppressed(String change) throws Exception {
        register("ANDROID_NATIVE_V10");var n=fixture("RECEIVED");plan(n);var claim=f.store.claimNext().orElseThrow();mutate(change,n);
        assertThat(f.store.prepare(claim)).isEmpty();
        assertThat(f.jdbc.queryForObject("select status from tbl_push_delivery where id=?",String.class,claim.id())).isEqualTo("SUPPRESSED");
    }
    @Test void listenerKeepsMainstagePermissionAndLosesAccessWhenSourceBecomesStudio() throws Exception {
        register("ANDROID_NATIVE_V10");var n=fixture("APPROVED");var role=UUID.randomUUID();
        f.jdbc.update("insert into tbl_role values(?,'ROLE_LISTENER')",role);f.jdbc.update("insert into user_roles values(?,?)",requester,role);
        assertThat(target(n)).isPresent();plan(n);var claim=f.store.claimNext().orElseThrow();
        f.jdbc.update("insert into tbl_studio_profile values(?)",author);
        assertThat(target(n)).isEmpty();assertThat(f.store.prepare(claim)).isEmpty();
    }
    @Test void forwardMigrationReplaysAndReadinessRejectsUnknownCapability() throws Exception {
        migration("overthinking");f.operations.run(new DefaultApplicationArguments(new String[0]));
        assertThatThrownBy(()->register("ANDROID_NATIVE_V11")).isInstanceOf(RuntimeException.class);
        assertThat(f.jdbc.queryForObject("select count(*) from soundconnect_schema_migrations where migration_id='2026-10-01-push-native-overthinking-capability'",Integer.class)).isOne();
    }
}
