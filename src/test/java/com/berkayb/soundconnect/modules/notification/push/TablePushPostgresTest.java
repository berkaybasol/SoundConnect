package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.*;
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
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated real PostgreSQL. No shared runtime, source replay, or clock mutation. */
@Testcontainers
class TablePushPostgresTest {
    private static final List<String> JDBC_OPT_IN_BEFORE_CLASS=PushFoundationPostgresTest.jdbcOptInSettings();
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("soundconnect_push_test").withUsername("push_test").withPassword("push_test");
    final PushFoundationPostgresTest f = new PushFoundationPostgresTest();
    final ObjectMapper json = new ObjectMapper();
    UUID table, cycle, owner, subject;
    @BeforeEach void setup() throws Exception {
        f.setup("jdbc:postgresql://127.0.0.1:"+DB.getMappedPort(5432)+"/soundconnect_push_test",DB.getUsername(),DB.getPassword());
        f.properties.setMaxDevicesPerUser(30); f.properties.setAllowedTypes(TablePushPresentation.TYPES);
        // Foundation fixture intentionally has only three legacy enum values.
        // Real TABLE schema already admits all seven notification types.
        f.jdbc.execute("alter table tbl_notification drop constraint legacy_notification_type");
        f.jdbc.execute("create table tbl_role(id uuid primary key,name text); create table user_roles(user_id uuid,role_id uuid)");
        f.jdbc.execute("create table tbl_studio_profile(user_id uuid)");
        f.jdbc.execute("create table tbl_table_group(id uuid primary key,owner_id uuid,description text,status text,expires_at timestamptz)");
        f.jdbc.execute("create table tbl_table_group_participants(table_group_id uuid,user_id uuid,status text,application_id uuid)");
        f.jdbc.execute("create table tbl_table_notification_event(event_id uuid primary key,recipient_id uuid,notification_type text,payload jsonb,occurred_at timestamptz)");
        f.jdbc.execute("create table tbl_notification_receipt(source_event_id uuid,recipient_id uuid)");
        f.jdbc.execute("alter table tbl_notification add column source_event_id uuid, add column payload jsonb, add column occurred_at timestamptz");
        f.jdbc.execute("insert into soundconnect_schema_migrations(migration_id) values('2026-09-30-table-notification-target')");
        migration();
        ReflectionTestUtils.setField(f.store,"policy",new NotificationDeliveryPolicy(new AccountDeliveryFence(f.sql),f.sql,f.transactionManager,mock(AfterCommitDeliveryExecutor.class)));
        table=UUID.randomUUID(); cycle=UUID.randomUUID();
    }
    @AfterEach void cleanup() { f.cleanup(); }
    @Test void containerFixtureDoesNotChangeJvmJdbcOptIn() {
        assertThat(PushFoundationPostgresTest.jdbcOptInSettings().equals(JDBC_OPT_IN_BEFORE_CLASS))
            .as("Container fixture must preserve the process-wide JDBC opt-in settings").isTrue();
    }
    void migration() throws Exception { f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-10-01-push-native-table-capability.sql"))); }
    UUID register(String version) {
        var id=UUID.randomUUID();
        f.tx.executeWithoutResult(s->f.devices.register(f.user,id,new PushDeviceService.Registration("table-fixture-"+id,PushDeviceService.Platform.ANDROID,PushDeviceService.Permission.AUTHORIZED,"test",1L,version)));
        return id;
    }
    Notification fixture(String type, String reason, boolean legacy) throws Exception {
        boolean ownerEvent=Set.of("TABLE_JOIN_REQUEST_RECEIVED","TABLE_PARTICIPANT_LEFT").contains(type);
        owner=ownerEvent?f.user:f.other; subject=ownerEvent?f.other:f.user;
        String action=type.equals("TABLE_REMOVED")?"PARTICIPANT_REMOVED":type.substring(6);
        var payload=new HashMap<String,Object>(Map.of("module","TABLE","tableGroupId",table.toString(),"action",action));
        if(ownerEvent) payload.put(type.equals("TABLE_PARTICIPANT_LEFT")?"leaverId":"applicantId",subject.toString());
        else if(!type.equals("TABLE_CANCELLED")) payload.put("ownerId",owner.toString());
        if(reason!=null) payload.put("reason",reason);
        if(!legacy) payload.put("applicationId",cycle.toString());
        String status=switch(type){case "TABLE_JOIN_REQUEST_RECEIVED"->"PENDING";case "TABLE_JOIN_REQUEST_REJECTED"->"REJECTED";case "TABLE_PARTICIPANT_LEFT"->"LEFT";case "TABLE_REMOVED"->"KICKED";default->"ACCEPTED";};
        String tableStatus=type.equals("TABLE_CANCELLED")?"CANCELLED":type.equals("TABLE_EXPIRED")?"INACTIVE":"ACTIVE";
        f.jdbc.update("insert into tbl_table_group values(?,?,'Private table',?,?)",table,owner,tableStatus,Timestamp.from(Instant.now().plusSeconds(86400)));
        f.jdbc.update("insert into tbl_table_group_participants values(?,?,?,?)",table,subject,status,cycle);
        var n=Notification.builder().sourceEventId(UUID.randomUUID()).recipientId(f.user).type(NotificationType.valueOf(type)).title("Private title").message("Private note").payload(payload).occurredAt(f.clock.instant()).read(false).build();
        ReflectionTestUtils.setField(n,"id",UUID.randomUUID());
        f.jdbc.update("insert into tbl_notification(id,recipient_id,type,source_event_id,payload,occurred_at) values(?,?,?,?,?::jsonb,?)",n.getId(),f.user,type,n.getSourceEventId(),json.writeValueAsString(payload),Timestamp.from(n.getOccurredAt()));
        f.jdbc.update("insert into tbl_table_notification_event values(?,?,?,?::jsonb,?)",n.getSourceEventId(),f.user,type,json.writeValueAsString(payload),Timestamp.from(n.getOccurredAt()));
        f.jdbc.update("insert into tbl_notification_receipt values(?,?)",n.getSourceEventId(),f.user);
        f.inbox.put(n.getId(),n); return n;
    }
    void plan(Notification n){ f.tx.executeWithoutResult(s->f.planner.plan(new NotificationPersisted(n))); }
    @ParameterizedTest @CsvSource({"TABLE_JOIN_REQUEST_RECEIVED,", "TABLE_JOIN_REQUEST_APPROVED,", "TABLE_JOIN_REQUEST_REJECTED,", "TABLE_PARTICIPANT_LEFT,", "TABLE_REMOVED,", "TABLE_CANCELLED,OWNER_CANCELLED", "TABLE_CANCELLED,OWNER_JOINED_ANOTHER_TABLE", "TABLE_EXPIRED,"})
    void everyOccurrencePlansOnlyV8AndPreparesClosedWire(String type,String reason) throws Exception {
        var v8=register(TablePushPresentation.CAPABILITY); register("ANDROID_NATIVE_V7"); register(null);
        var n=fixture(type,reason,type.equals("TABLE_EXPIRED")); plan(n); plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(1);
        var claim=f.store.claimNext().orElseThrow(); assertThat(claim.installationId()).isEqualTo(v8);
        var wire=f.store.prepare(claim).orElseThrow().data();
        assertThat(wire).containsEntry("type",type).containsEntry("presentationVersion",TablePushPresentation.VERSION)
                .containsEntry("displayVariant",reason==null?"DEFAULT":reason);
        assertThat(wire.keySet()).containsExactlyInAnyOrder("notificationId","recipientId","type","presentationVersion","displayVariant","sentAt","expiresAt");
    }
    @ParameterizedTest @ValueSource(strings={"receipt","source","table","wrongRecipient","wrongType","wrongAction","wrongCycle","wrongModule","read","recipientErased","subjectErased","ownerErased","role","sourceTime"})
    void planningRejectsMissingOrMalformedSourceAndAuthority(String mutation) throws Exception {
        register(TablePushPresentation.CAPABILITY); var n=fixture("TABLE_JOIN_REQUEST_RECEIVED",null,false); mutate(mutation); plan(n);
        assertThat(f.jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
    }
    void mutate(String change) {
        switch(change) {
            case "receipt" -> f.jdbc.execute("delete from tbl_notification_receipt");
            case "source" -> f.jdbc.execute("delete from tbl_table_notification_event");
            case "table" -> f.jdbc.execute("delete from tbl_table_group");
            case "wrongRecipient" -> f.jdbc.update("update tbl_notification set recipient_id=?",f.other);
            case "wrongType" -> f.jdbc.execute("update tbl_table_notification_event set notification_type='TABLE_REMOVED'");
            case "wrongAction", "wrongCycle", "wrongModule" -> {
                String key=change.equals("wrongAction")?"action":change.equals("wrongCycle")?"applicationId":"module";
                for(String t:List.of("tbl_notification","tbl_table_notification_event")) f.jdbc.update("update "+t+" set payload=jsonb_set(payload,array[?],to_jsonb('invalid'::text))",key);
            }
            case "read" -> f.jdbc.execute("update tbl_notification set is_read=true");
            case "sourceTime" -> f.jdbc.execute("update tbl_table_notification_event set occurred_at=occurred_at-interval '1 second'");
            case "recipientErased" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",f.user);
            case "subjectErased" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",subject);
            case "ownerErased" -> f.jdbc.update("update tbl_user set erased_at=now() where id=?",owner);
            case "role" -> {var role=UUID.randomUUID(); f.jdbc.update("insert into tbl_role values(?,'ROLE_STUDIO')",role);f.jdbc.update("insert into user_roles values(?,?)",f.user,role);}
            case "downgrade" -> f.jdbc.execute("update tbl_push_device set presentation_version='ANDROID_NATIVE_V7'");
            case "generation" -> f.jdbc.execute("update tbl_push_device set generation=generation+1");
            case "deviceOwner" -> f.jdbc.update("update tbl_push_device set user_id=?",f.other);
            case "permission" -> f.jdbc.execute("update tbl_push_device set permission='DENIED'");
            case "preference" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(false,Set.of())));
            case "category" -> f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.user,new PushDeviceService.Preferences(true,Set.of("TABLE"))));
            default -> throw new IllegalArgumentException(change);
        }
    }
    @ParameterizedTest @ValueSource(strings={"receipt","source","wrongRecipient","wrongType","wrongAction","wrongCycle","read","recipientErased","role","sourceTime","downgrade","generation","deviceOwner","permission","preference","category"})
    void sendRevalidatesAfterPlanning(String change) throws Exception {
        register(TablePushPresentation.CAPABILITY);var n=fixture("TABLE_JOIN_REQUEST_RECEIVED",null,false);plan(n);
        var claim=f.store.claimNext().orElseThrow();mutate(change); assertThat(f.store.prepare(claim)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"TABLE_JOIN_REQUEST_RECEIVED","TABLE_JOIN_REQUEST_APPROVED","TABLE_JOIN_REQUEST_REJECTED","TABLE_PARTICIPANT_LEFT","TABLE_REMOVED"})
    void oldCycleRemainsProvenHistoryAndNeverBindsNewApplication(String type) throws Exception {
        register(TablePushPresentation.CAPABILITY); var n=fixture(type,null,false);
        f.jdbc.update("update tbl_table_group_participants set application_id=?,status='ACCEPTED'",UUID.randomUUID());
        plan(n);assertThat(f.store.prepare(f.store.claimNext().orElseThrow())).isPresent();
        var target=new TableNotificationTargetService(f.sql).resolve(f.user,n.getId());
        assertThat(target.kind().name()).isEqualTo("RESULT");assertThat(target.sameApplication()).isFalse();assertThat(target.applicationId()).isEqualTo(cycle);
    }
    @ParameterizedTest @ValueSource(strings={"TABLE_JOIN_REQUEST_RECEIVED","TABLE_JOIN_REQUEST_APPROVED","TABLE_EXPIRED"})
    void provenLegacyWithoutCycleCanOnlyBeHistorical(String type) throws Exception {
        register(TablePushPresentation.CAPABILITY);var n=fixture(type,null,true);plan(n);
        assertThat(f.store.prepare(f.store.claimNext().orElseThrow())).isPresent();
        assertThat(new TableNotificationTargetService(f.sql).resolve(f.user,n.getId()).kind().name()).isEqualTo("RESULT");
    }
    @Test void migrationIsForwardIdempotentAndReadinessRequiresIt() throws Exception {
        register("ANDROID_NATIVE_V7"); var before=f.jdbc.queryForList("select * from tbl_push_device");
        var markers=f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id");
        migration(); migration();
        assertThat(f.jdbc.queryForList("select * from tbl_push_device")).isEqualTo(before);
        assertThat(f.jdbc.queryForList("select * from soundconnect_schema_migrations order by migration_id")).isEqualTo(markers);
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-overthinking-capability");
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-10-01-push-native-collab-capability.sql")));
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-10-01-push-native-overthinking-capability.sql")));
        f.operations.run(new DefaultApplicationArguments());
        assertThat(f.jdbc.queryForList("select * from tbl_push_device")).isEqualTo(before);
        f.jdbc.execute("delete from soundconnect_schema_migrations where migration_id='2026-10-01-push-native-table-capability'");
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-table-capability");
    }
    @Test void expiryNativePlanAndWireThenRealOwnedHttpRemainUnread() throws Exception {
        register(TablePushPresentation.CAPABILITY); var n=fixture("TABLE_EXPIRED",null,true); plan(n);
        var wire=f.store.prepare(f.store.claimNext().orElseThrow()).orElseThrow().data();
        try(var context=new org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext()) {
            context.registerBean(TableNotificationTargetService.class,()->new TableNotificationTargetService(f.sql));
            context.registerBean(com.berkayb.soundconnect.auth.security.UserDetailsImpl.class,()->
                new com.berkayb.soundconnect.auth.security.UserDetailsImpl(com.berkayb.soundconnect.modules.user.entity.User.builder().id(f.user).build()));
            context.register(ExpiryHttpFixture.class); context.refresh();
            var uri=java.net.URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/api/v1/user/notifications/"+wire.get("notificationId")+"/table-target");
            var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(uri).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var data=json.readTree(response.body()).path("data");
            assertThat(data.path("notificationId").asText()).isEqualTo(n.getId().toString());
            assertThat(data.path("type").asText()).isEqualTo("TABLE_EXPIRED");
            assertThat(data.path("kind").asText()).isEqualTo("RESULT");
            assertThat(data.path("applicationId").isNull()).isTrue();
            assertThat(data.path("read").asBoolean()).isFalse();
            assertThat(f.jdbc.queryForObject("select is_read from tbl_notification",Boolean.class)).isFalse();
            var path=Path.of("build/test-evidence/table-push/isolated-expiry-http.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("environment","disposable PostgreSQL + embedded Tomcat; test principal; no shared worker/FCM/device", "wire",wire,"httpStatus",response.statusCode(),"response",data)));
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class ExpiryHttpFixture {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.servlet.server.ServletWebServerFactory server() {
            return new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory(0);
        }
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.servlet.ServletRegistrationBean<org.springframework.web.servlet.DispatcherServlet> dispatcher(org.springframework.web.context.WebApplicationContext context) {
            return new org.springframework.boot.web.servlet.ServletRegistrationBean<>(new org.springframework.web.servlet.DispatcherServlet(context),"/");
        }
        @org.springframework.context.annotation.Bean
        com.berkayb.soundconnect.modules.notification.controller.user.TableNotificationController controller(TableNotificationTargetService service) {
            return new com.berkayb.soundconnect.modules.notification.controller.user.TableNotificationController(service);
        }
        @org.springframework.context.annotation.Bean
        org.springframework.web.servlet.config.annotation.WebMvcConfigurer fixturePrincipal(com.berkayb.soundconnect.auth.security.UserDetailsImpl principal) {
            return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
                @Override public void addArgumentResolvers(List<org.springframework.web.method.support.HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                        public boolean supportsParameter(org.springframework.core.MethodParameter p) {
                            return p.hasParameterAnnotation(org.springframework.security.core.annotation.AuthenticationPrincipal.class);
                        }
                        public Object resolveArgument(org.springframework.core.MethodParameter p,org.springframework.web.method.support.ModelAndViewContainer m,
                            org.springframework.web.context.request.NativeWebRequest r,org.springframework.web.bind.support.WebDataBinderFactory b) { return principal; }
                    });
                }
                @Override public void configureMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
                    converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()
                        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)));
                }
            };
        }
    }
}
