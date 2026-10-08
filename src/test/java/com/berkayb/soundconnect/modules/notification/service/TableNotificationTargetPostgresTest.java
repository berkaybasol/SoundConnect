package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.TableNotificationTargetResponse.Kind;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real SQL, migration, JSON and source/recipient/cycle joins in disposable PostgreSQL. */
@Testcontainers
class TableNotificationTargetPostgresTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("table_target_test").withUsername("target_test").withPassword("target_test");
    JdbcTemplate jdbc;
    TableNotificationTargetService service;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final UUID owner=UUID.randomUUID(),subject=UUID.randomUUID(),stranger=UUID.randomUUID(),
            table=UUID.randomUUID(),cycle=UUID.randomUUID(),event=UUID.randomUUID(),notification=UUID.randomUUID();
    final Instant at=Instant.parse("2026-09-30T10:00:00Z");
    UUID reader;

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("drop schema public cascade; create schema public");
        jdbc.execute("create table tbl_user(id uuid primary key,status text,email_verified boolean,erased_at timestamptz)");
        jdbc.execute("create table tbl_role(id uuid primary key,name text); create table user_roles(user_id uuid,role_id uuid)");
        jdbc.execute("create table tbl_studio_profile(user_id uuid); create table tbl_venues(owner_id uuid)");
        jdbc.execute("create table tbl_table_group(id uuid primary key,owner_id uuid,description text,status text,expires_at timestamptz)");
        jdbc.execute("create table tbl_table_group_participants(table_group_id uuid,user_id uuid,status text,joined_at timestamptz, unique(table_group_id,user_id))");
        jdbc.execute("create table tbl_table_group_notification_outbox(event_id uuid primary key,recipient_id uuid,notification_type varchar(64),payload jsonb,occurred_at timestamptz)");
        jdbc.execute("create table tbl_notification_receipt(source_event_id uuid primary key,recipient_id uuid)");
        jdbc.execute("create table tbl_notification(id uuid primary key,source_event_id uuid unique,recipient_id uuid,type varchar(64),payload jsonb,is_read boolean default false)");
        migration();
        for(UUID u:List.of(owner,subject,stranger)) jdbc.update("insert into tbl_user values(?,'ACTIVE',true,null)",u);
        jdbc.update("insert into tbl_table_group values(?,?,'Güncel masa','ACTIVE',?)",table,owner,Timestamp.from(Instant.now().plusSeconds(86400)));
        jdbc.update("insert into tbl_table_group_participants values(?,?,'PENDING',?,?)",table,subject,Timestamp.from(at),cycle);
        service=new TableNotificationTargetService(new NamedParameterJdbcTemplate(ds));
    }
    void migration() throws Exception {
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-30-table-notification-target.sql")));
    }
    void fixture(String suffix, String reason, boolean bound) throws Exception {
        boolean ownerEvent=Set.of("JOIN_REQUEST_RECEIVED","PARTICIPANT_LEFT").contains(suffix);
        reader=ownerEvent?owner:subject;
        String action=suffix.equals("REMOVED")?"PARTICIPANT_REMOVED":suffix;
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("module","TABLE");payload.put("tableGroupId",table.toString());payload.put("action",action);
        if (suffix.equals("CANCELLED")) payload.put("reason",reason);
        else payload.put(suffix.equals("JOIN_REQUEST_RECEIVED")?"applicantId":suffix.equals("PARTICIPANT_LEFT")?"leaverId":"ownerId",
                (ownerEvent?subject:owner).toString());
        if(bound)payload.put("applicationId",cycle.toString());
        String state=switch(suffix){case "JOIN_REQUEST_RECEIVED"->"PENDING";case "JOIN_REQUEST_REJECTED"->"REJECTED";case "REMOVED"->"KICKED";case "PARTICIPANT_LEFT"->"LEFT";default->"ACCEPTED";};
        jdbc.update("update tbl_table_group_participants set status=?",state);
        if(suffix.equals("CANCELLED"))jdbc.update("update tbl_table_group set status='CANCELLED'");
        if(suffix.equals("EXPIRED"))jdbc.update("update tbl_table_group set status='INACTIVE'");
        jdbc.update("insert into tbl_table_group_notification_outbox values(?,?,?,?::jsonb,?)",event,reader,"TABLE_"+suffix,json.writeValueAsString(payload),Timestamp.from(at));
        jdbc.update("insert into tbl_notification_receipt values(?,?)",event,reader);
        jdbc.update("insert into tbl_notification values(?,?,?,?,?::jsonb,false)",notification,event,reader,"TABLE_"+suffix,json.writeValueAsString(payload));
    }
    @ParameterizedTest @CsvSource({
        "JOIN_REQUEST_RECEIVED,,PENDING_APPLICATION","JOIN_REQUEST_APPROVED,,CHAT","JOIN_REQUEST_REJECTED,,RESULT",
        "PARTICIPANT_LEFT,,RESULT","REMOVED,,RESULT","CANCELLED,OWNER_CANCELLED,RESULT",
        "CANCELLED,OWNER_JOINED_ANOTHER_TABLE,RESULT","EXPIRED,,RESULT"})
    void allSevenTypesAndBothCancellationReasons(String suffix,String reason,Kind kind) throws Exception {
        fixture(suffix,reason,true);
        var r=service.resolve(reader,notification);
        assertThat(r.kind()).isEqualTo(kind);assertThat(r.reason()).isEqualTo(reason);
        assertThat(r.notificationId()).isEqualTo(notification);assertThat(r.tableGroupId()).isEqualTo(table);
        assertThat(r.subjectId()).isEqualTo(subject);assertThat(r.applicationId()).isEqualTo(cycle);
        assertThat(r.occurredAt()).isEqualTo(at);assertThat(r.read()).isFalse();
        assertThat(json.writeValueAsString(r)).doesNotContain("joinNote","participants","username","avatar","payload","messages");
        unavailable(stranger);
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where is_read",Integer.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"JOIN_REQUEST_RECEIVED","JOIN_REQUEST_APPROVED","JOIN_REQUEST_REJECTED","PARTICIPANT_LEFT","REMOVED"})
    void oldCycleNeverOpensNewPendingOrAcceptedCycle(String suffix) throws Exception {
        fixture(suffix,null,true);
        for(String state:List.of("PENDING","ACCEPTED")) {
            jdbc.update("update tbl_table_group_participants set application_id=?,status=?",UUID.randomUUID(),state);
            var r=service.resolve(reader,notification);
            assertThat(r.kind()).isEqualTo(Kind.RESULT);assertThat(r.sameApplication()).isFalse();
            assertThat(r.participantStatus()).isEqualTo(state);assertThat(r.applicationId()).isEqualTo(cycle);
        }
    }
    @ParameterizedTest @ValueSource(strings={"REJECTED","KICKED","LEFT"})
    void approvalAfterMembershipEndsIsHistory(String status) throws Exception {
        fixture("JOIN_REQUEST_APPROVED",null,true);
        jdbc.update("update tbl_table_group_participants set status=?",status);
        assertThat(service.resolve(reader,notification).kind()).isEqualTo(Kind.RESULT);
    }
    @ParameterizedTest @ValueSource(strings={"CANCELLED","INACTIVE"})
    void approvalAfterClosureCannotReopenChat(String status) throws Exception {
        fixture("JOIN_REQUEST_APPROVED",null,true);jdbc.update("update tbl_table_group set status=?",status);
        assertThat(service.resolve(reader,notification).kind()).isEqualTo(Kind.RESULT);
    }
    @Test void pastDeadlineDoesNotInventExpiry() throws Exception {
        fixture("JOIN_REQUEST_APPROVED",null,true);
        jdbc.update("update tbl_table_group set expires_at=?",Timestamp.from(at.minusSeconds(1)));
        var r=service.resolve(reader,notification);assertThat(r.kind()).isEqualTo(Kind.RESULT);
        assertThat(r.tableStatus()).isEqualTo("ACTIVE");
    }
    @ParameterizedTest @ValueSource(strings={"JOIN_REQUEST_RECEIVED","JOIN_REQUEST_APPROVED","JOIN_REQUEST_REJECTED","PARTICIPANT_LEFT","REMOVED","EXPIRED"})
    void legacyProducerWithoutCycleHasProvenHistoryButNoActiveRoute(String suffix) throws Exception {
        fixture(suffix,null,false);
        var r=service.resolve(reader,notification);assertThat(r.kind()).isEqualTo(Kind.RESULT);
        assertThat(r.sameApplication()).isFalse();assertThat(r.applicationId()).isNull();
        // Normal retention must not destroy provenance. Permanent receipt stays minimal.
        jdbc.update("delete from tbl_table_group_notification_outbox");
        migration();assertThat(service.resolve(reader,notification)).isEqualTo(r);
    }
    @ParameterizedTest @ValueSource(strings={"tableGroupId","module","action","ownerId","applicationId"})
    void tamperedPayloadCannotServeAsHistoricalProof(String key) throws Exception {
        fixture("JOIN_REQUEST_APPROVED",null,true);
        jdbc.update("update tbl_notification set payload=jsonb_set(payload,array[?],to_jsonb('forged'::text))",key);
        unavailable(reader);
    }
    @ParameterizedTest @ValueSource(strings={"module","action","tableGroupId","ownerId","applicationId"})
    void evenSourcePayloadMustPassTypeAndSourceValidation(String key) throws Exception {
        fixture("JOIN_REQUEST_APPROVED",null,true);
        for(String tableName:List.of("tbl_notification","tbl_table_notification_event"))
            jdbc.update("update "+tableName+" set payload=jsonb_set(payload,array[?],to_jsonb('wrong'::text))",key);
        unavailable(reader);
    }
    @Test void missingSourceReceiptGroupAndForeignNotificationAreIndistinguishable() throws Exception {
        fixture("JOIN_REQUEST_RECEIVED",null,true);unavailable(stranger);
        assertThatThrownBy(()->service.resolve(reader,UUID.randomUUID())).isInstanceOf(SoundConnectException.class).hasFieldOrPropertyWithValue("errorType",ErrorType.NOTIFICATION_NOT_FOUND);
        jdbc.update("delete from tbl_notification_receipt");unavailable(reader);
        jdbc.update("insert into tbl_notification_receipt values(?,?)",event,reader);
        jdbc.update("delete from tbl_table_notification_event");unavailable(reader);
        migration();jdbc.update("delete from tbl_table_group");unavailable(reader);
    }
    @ParameterizedTest @ValueSource(strings={"recipient","owner","subject"})
    void erasedOrInactiveAccountsCannotResolve(String who) throws Exception {
        fixture("JOIN_REQUEST_APPROVED",null,true);
        UUID user=who.equals("owner")?owner:subject;
        jdbc.update("update tbl_user set erased_at=now() where id=?",user);unavailable(reader);
        jdbc.update("update tbl_user set erased_at=null,status='INACTIVE' where id=?",user);unavailable(reader);
    }
    @ParameterizedTest @ValueSource(strings={"ROLE_VENUE","ROLE_STUDIO"})
    void institutionalActorsAreForbiddenButListenersAreAllowed(String role) throws Exception {
        fixture("JOIN_REQUEST_RECEIVED",null,true);
        UUID roleId=UUID.randomUUID();jdbc.update("insert into tbl_role values(?,'ROLE_LISTENER')",roleId);
        jdbc.update("insert into user_roles values(?,?)",owner,roleId);
        assertThat(service.resolve(owner,notification).kind()).isEqualTo(Kind.PENDING_APPLICATION);
        jdbc.update("update tbl_role set name=?",role);unavailable(reader);
    }
    @Test void migrationReplayPreservesProofAndLegacyExpiryWriterCanStillInsert() throws Exception {
        fixture("EXPIRED",null,false);
        String before=jdbc.queryForObject("select row_to_json(e)::text from tbl_table_notification_event e",String.class);
        migration();migration();assertThat(jdbc.queryForObject("select row_to_json(e)::text from tbl_table_notification_event e",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from soundconnect_schema_migrations",Integer.class)).isEqualTo(1);
        assertThat(service.resolve(reader,notification).event()).isEqualTo("EXPIRED");
    }

    @Test void restrictedOldProducerNeedsNoGrantToNewProofTable() throws Exception {
        jdbc.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='legacy_table_writer') then create role legacy_table_writer; end if; end $$");
        jdbc.execute("grant usage on schema public to legacy_table_writer");
        jdbc.execute("grant insert on tbl_table_group_notification_outbox to legacy_table_writer");
        // Old Hibernate collection rewrites omit the new nullable column.
        jdbc.update("delete from tbl_table_group_participants");
        jdbc.update("insert into tbl_table_group_participants(table_group_id,user_id,status,joined_at) values(?,?,'ACCEPTED',?)",table,subject,Timestamp.from(at));
        jdbc.update("update tbl_table_group set status='INACTIVE'");
        try(var c=jdbc.getDataSource().getConnection(); var statement=c.createStatement()) {
            statement.execute("set role legacy_table_writer");
            statement.execute("insert into tbl_table_group_notification_outbox values('"+event+"','"+subject+"','TABLE_EXPIRED','{\"module\":\"TABLE\",\"action\":\"EXPIRED\",\"tableGroupId\":\""+table+"\",\"ownerId\":\""+owner+"\"}',now())");
        }
        jdbc.update("insert into tbl_notification_receipt values(?,?)",event,subject);
        jdbc.update("insert into tbl_notification select ?,event_id,recipient_id,notification_type,payload,false from tbl_table_group_notification_outbox",notification);
        var result=service.resolve(subject,notification);
        assertThat(result.kind()).isEqualTo(Kind.RESULT);assertThat(result.applicationId()).isNull();
        assertThat(result.event()).isEqualTo("EXPIRED");
    }
    void unavailable(UUID recipient) {
        assertThatThrownBy(()->service.resolve(recipient,notification)).isInstanceOf(SoundConnectException.class)
                .hasFieldOrPropertyWithValue("errorType",ErrorType.NOTIFICATION_NOT_FOUND);
    }

    @Test void legacyExpiryCrossesRealHttpAndSurvivesOutboxRetentionWithoutRead() throws Exception {
        fixture("EXPIRED",null,false);
        // Isolated real HTTP transport, production controller/resolver/DTO and PostgreSQL.
        // Principal is a test fixture; normal JWT/ownership acceptance is measured on the shared API separately.
        try(var context=new org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext()) {
            context.registerBean(TableNotificationTargetService.class,()->service);
            context.registerBean(com.berkayb.soundconnect.auth.security.UserDetailsImpl.class,()->
                new com.berkayb.soundconnect.auth.security.UserDetailsImpl(
                    com.berkayb.soundconnect.modules.user.entity.User.builder().id(subject).build()));
            context.register(ExpiryHttpFixture.class);context.refresh();
            var client=java.net.http.HttpClient.newHttpClient();
            var uri=java.net.URI.create("http://127.0.0.1:"+context.getWebServer().getPort()
                +"/api/v1/user/notifications/"+notification+"/table-target");
            var first=client.send(java.net.http.HttpRequest.newBuilder(uri).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(200);
            var data=json.readTree(first.body()).path("data");
            assertThat(data.path("event").asText()).isEqualTo("EXPIRED");
            assertThat(data.path("kind").asText()).isEqualTo("RESULT");
            assertThat(data.path("tableStatus").asText()).isEqualTo("INACTIVE");
            assertThat(data.path("participantStatus").asText()).isEqualTo("ACCEPTED");
            assertThat(data.path("occurredAt").asText()).isEqualTo(at.toString());
            assertThat(data.path("applicationId").isNull()).isTrue();
            jdbc.update("delete from tbl_table_group_notification_outbox");migration();
            var second=client.send(java.net.http.HttpRequest.newBuilder(uri).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(second.statusCode()).isEqualTo(200);assertThat(second.body()).isEqualTo(first.body());
            assertThat(jdbc.queryForObject("select is_read from tbl_notification where id=?",Boolean.class,notification)).isFalse();
            var path=Path.of("build/reports/table-expiry-real-http.json");Files.createDirectories(path.getParent());
            Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "environment","disposable PostgreSQL + embedded Tomcat; test principal; no shared DB/time changes",
                "firstStatus",first.statusCode(),"afterSourceRetentionStatus",second.statusCode(),
                "target",data,"notificationStillUnread",true,"receiptCount",jdbc.queryForObject("select count(*) from tbl_notification_receipt",Integer.class))));
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
