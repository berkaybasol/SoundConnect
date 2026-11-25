package com.berkayb.soundconnect.modules.tablegroup.report.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.tablegroup.chat.service.TableGroupChatService;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import com.berkayb.soundconnect.modules.tablegroup.report.TableGroupReportRequest.Kind;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.berkayb.soundconnect.modules.tablegroup.report.admin.TableReportModeration.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class TableReportModerationPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    static NamedParameterJdbcTemplate jdbc;
    static TransactionTemplate transaction;
    static UUID adminRole = UUID.randomUUID();
    final UUID admin = UUID.randomUUID(), table = UUID.randomUUID(), message = UUID.randomUUID();
    final UUID report = UUID.randomUUID(), incident = UUID.randomUUID();
    final TableGroupService tables = mock(TableGroupService.class);
    final TableGroupChatService chats = mock(TableGroupChatService.class);
    UserDetailsImpl actor;
    TableReportModeration service;

    @BeforeAll static void install() throws Exception {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.getJdbcTemplate().execute("""
                create table tbl_user(id uuid primary key,user_name text, status text default 'ACTIVE',
                  email_verified boolean default true, erased_at timestamptz, session_version bigint default 0,
                  access_suspended boolean default false);
                create table tbl_permissions(id uuid primary key,name text unique,created_at timestamp,updated_at timestamp);
                create table tbl_role(id uuid primary key,name text);
                create table role_permissions(role_id uuid,permission_id uuid);
                create table user_permissions(user_id uuid,permission_id uuid);
                create table user_roles(user_id uuid,role_id uuid);
                create table "tbl_listener-profile"(user_id uuid);
                create table tbl_city(id uuid primary key,name text);
                create table tbl_district(id uuid primary key,name text);
                create table tbl_table_group(id uuid primary key,status text,expires_at timestamptz,description text,
                    owner_id uuid, city_id uuid, district_id uuid, venue_name text, meeting_at timestamptz,
                    max_person_count integer default 4);
                create table tbl_table_group_profile_share(id uuid primary key,table_group_id uuid,owner_user_id uuid);
                create table soundconnect_schema_migrations(migration_id text primary key);
                create table tbl_table_group_participants(user_id uuid,status text,table_group_id uuid);
                create table tbl_table_group_message(id uuid primary key,table_group_id uuid,message_type text,deleted_at timestamp);
                create table tbl_table_notification_event(payload jsonb,occurred_at timestamptz,recipient_id uuid,notification_type text);
                """);
        jdbc.update("insert into tbl_role values(:id,'ROLE_ADMIN')",Map.of("id",adminRole));
        jdbc.update("insert into tbl_permissions(id,name) values(gen_random_uuid(),'ADMIN_PANEL_ACCESS')",Map.of());
        jdbc.update("insert into role_permissions select :id,id from tbl_permissions",Map.of("id",adminRole));
        for (String file : List.of("2026-09-10-tablegroup-profile-share-history.sql","2026-11-24-table-group-reports.sql",
                "2026-11-24-table-report-permission.sql"))
            jdbc.getJdbcTemplate().execute(Files.readString(Path.of("scripts","db",file)));
    }

    @BeforeEach void fixtures() {
        jdbc.getJdbcTemplate().execute("truncate tbl_table_group_report,tbl_user,tbl_table_group,tbl_table_group_message,user_roles,tbl_table_group_profile_share,tbl_city");
        Map<String,Object> ids=Map.of("a",admin,"role",adminRole,"t",table,"m",message,"r",report,"i",incident);
        jdbc.update("insert into tbl_user(id,user_name) values(:a,'editor')",ids);
        jdbc.update("insert into user_roles values(:a,:role)",ids);
        jdbc.update("insert into tbl_city values(:t,'Ankara')",ids);
        jdbc.update("""
                insert into tbl_table_group(id,status,expires_at,description,owner_id,city_id,meeting_at)
                values(:t,'ACTIVE',now()+interval '1 hour','Public text',:a,:t,now())
                """,ids);
        jdbc.update("insert into tbl_table_group_message values(:m,:t,'TEXT',null)",ids);
        jdbc.update("""
                insert into tbl_table_group_report(id,request_id,table_group_id,kind,reason,reported_at,
                  table_description,message_id,message_text,message_sent_at,reporter_username)
                values(:r,gen_random_uuid(),:t,'MESSAGE','MONEY_REQUEST',now(),'Public text',:m,'selected text',now(),'reporter')
                """,ids);
        jdbc.update("""
                insert into tbl_table_group_report(id,request_id,table_group_id,kind,reason,reported_at,description)
                values(:i,gen_random_uuid(),:t,'INCIDENT','OTHER',now(),'Another event')
                """,ids);
        actor=UserDetailsImpl.fromUser(User.builder().id(admin).username("editor").build());
        service=new TableReportModeration(jdbc,new AdminPanelReadAccess(jdbc),new AccountDeliveryFence(jdbc),tables,chats);
    }

    @Test void minimalListIndependentResolutionReplayAndPermissionRevocation() {
        assertThat(service.list(actor,Status.OPEN,Kind.MESSAGE,0,10).content()).singleElement().satisfies(
                item -> assertThat(item.id()).isEqualTo(report));
        var evidence=service.detail(actor,report);
        assertThat(evidence.messageText()).isEqualTo("selected text");
        assertThat(evidence.messageRemovable()).isTrue();
        var decision=new Review(Decision.REMOVE_MESSAGE,"Checked content",0L);
        var saved=review(report,decision);
        assertThat(saved.status()).isEqualTo("RESOLVED");
        assertThat(saved.reviewerUsername()).isEqualTo("editor");
        assertThat(review(report,decision).reviewedAt()).isEqualTo(saved.reviewedAt());
        verify(chats,times(1)).removeReportedMessage(table,message);
        assertThat(service.detail(actor,incident).status()).isEqualTo("OPEN");
        assertThatThrownBy(() -> review(report,new Review(Decision.DISMISS,"Another note",1L)))
                .hasFieldOrPropertyWithValue("errorType",ErrorType.TABLE_GROUP_REPORT_STATE_CHANGED);
        jdbc.update("delete from user_roles where user_id=:a",Map.of("a",admin));
        assertThatThrownBy(()->service.detail(actor,report)).hasFieldOrPropertyWithValue("errorType",ErrorType.FORBIDDEN_ACCESS);
        assertThatThrownBy(()->review(incident,decision)).hasFieldOrPropertyWithValue("errorType",ErrorType.FORBIDDEN_ACCESS);
    }

    @Test void onlyTheOriginalReviewerCanReplayTheDecisionFromItsOriginalVersion() {
        var other = secondReviewer();
        var decision = new Review(Decision.REMOVE_MESSAGE, "Checked content", 0L);
        var saved = review(report, decision);

        assertThatThrownBy(() -> transaction.execute(ignored -> service.review(other, report, decision)))
                .hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_REPORT_STATE_CHANGED);
        var wrongVersion = new Review(decision.decision(), decision.resolutionNote(), saved.version());
        assertThatThrownBy(() -> review(report, wrongVersion))
                .hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_REPORT_STATE_CHANGED);
        assertThat(review(report, decision)).isEqualTo(saved);
        verify(chats, times(1)).removeReportedMessage(table, message);
    }

    @Test void openAndResolvedListsRefreshAndKeepStablePagesForEachFilter() {
        var first = service.list(actor,Status.OPEN,null,0,1);
        var second = service.list(actor,Status.OPEN,null,1,1);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(List.of(first.content().getFirst().id(),second.content().getFirst().id()))
                .containsExactlyInAnyOrder(report,incident);
        assertThat(service.list(actor,Status.OPEN,Kind.LISTING,0,1).content()).isEmpty();
        assertThat(service.list(actor,Status.RESOLVED,null,0,1).content()).isEmpty();
        var messageSummary = service.list(actor,Status.OPEN,Kind.MESSAGE,0,1).content().getFirst();
        var full = service.detail(actor,report);
        assertThat(messageSummary.id()).isEqualTo(full.id());
        assertThat(messageSummary.reportedAt()).isEqualTo(full.reportedAt());
        assertThat(messageSummary.reporterUsername()).isEqualTo("reporter");
        assertThat(messageSummary.reason()).isEqualTo("MONEY_REQUEST");
        review(report,new Review(Decision.DISMISS,"Reviewed listing",0L));
        assertThat(service.list(actor,Status.OPEN,Kind.MESSAGE,0,1).content()).isEmpty();
        assertThat(service.list(actor,Status.RESOLVED,Kind.MESSAGE,0,1).content()).singleElement()
                .satisfies(row -> assertThat(row.id()).isEqualTo(report));
    }

    @Test void cancellationFailureRollsBackRedactionAndLeavesReportOpen() {
        doThrow(new IllegalStateException("outbox failure")).when(tables).closeForModeration(table);
        assertThatThrownBy(()->review(report,new Review(Decision.REMOVE_TABLE,"Content removed",0L)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(actor,report).tableRemoved()).isFalse();
        assertThat(service.detail(actor,report).status()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select description from tbl_table_group where id=:t",Map.of("t",table),String.class))
                .isEqualTo("Public text");
    }

    @Test void removalFreezesNeutralDescriptionWithoutDestroyingTheProfilePublicationOrOtherReports() {
        jdbc.update("insert into tbl_table_group_profile_share(id,table_group_id,owner_user_id) values(:id,:t,:a)",
                Map.of("id",UUID.randomUUID(),"t",table,"a",admin));
        doAnswer(call -> jdbc.update("update tbl_table_group set status='CANCELLED' where id=:t",Map.of("t",table)))
                .when(tables).closeForModeration(table);
        var result=review(report,new Review(Decision.REMOVE_TABLE,"Verified report",0L));
        assertThat(result.tableRemoved()).isTrue();
        assertThat(result.tableActive()).isFalse();
        assertThat(result.tableDescription()).isEqualTo("Public text");
        assertThat(jdbc.queryForObject("""
                select final_source->>'description' from tbl_table_group_profile_share where table_group_id=:t
                """,Map.of("t",table),String.class)).isEqualTo("Bu masa yayından kaldırıldı.");
        assertThat(service.detail(actor,incident).status()).isEqualTo("OPEN");
    }

    @Test void closedOrMissingSourceKeepsEvidenceAndOnlyAcceptsApplicableDecisions() {
        jdbc.update("delete from tbl_table_group_message where id=:m",Map.of("m",message));
        jdbc.update("delete from tbl_table_group where id=:t",Map.of("t",table));
        var details=service.detail(actor,report);
        assertThat(details.tableActive()).isFalse();
        assertThat(details.messageRemovable()).isFalse();
        assertThat(details.messageText()).isEqualTo("selected text");
        assertThatThrownBy(()->review(report,new Review(Decision.REMOVE_MESSAGE,"Content removed",0L)))
                .hasFieldOrPropertyWithValue("errorType",ErrorType.TABLE_GROUP_REPORT_STATE_CHANGED);
        assertThat(review(report,new Review(Decision.CONFIRM_VIOLATION,"Verified evidence",0L)).version()).isEqualTo(1);
    }

    @Test void differentConcurrentDecisionsNeverOverwriteTheWinner() throws Exception {
        var other = secondReviewer();
        assertThat(service.detail(other, incident).version()).isZero();
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Boolean>>();
            var choices = Map.of(Decision.DISMISS, actor, Decision.CONFIRM_VIOLATION, other);
            for (var entry : choices.entrySet()) {
                results.add(executor.submit(()->{
                    gate.await();
                    try {
                        transaction.execute(ignored -> service.review(entry.getValue(), incident,
                                new Review(entry.getKey(),"Review complete",0L)));
                        return true;
                    }
                    catch (SoundConnectException failure) {
                        assertThat(failure.getErrorType()).isEqualTo(ErrorType.TABLE_GROUP_REPORT_STATE_CHANGED);
                        return false;
                    }
                }));
            }
            gate.countDown();
            assertThat(List.of(results.get(0).get(10,TimeUnit.SECONDS), results.get(1).get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        var result = service.detail(actor,incident);
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.reviewerUsername()).isEqualTo("DISMISS".equals(result.decision()) ? "editor" : "editor2");
        assertThat(service.detail(other,incident)).isEqualTo(result);
    }

    @Test void anotherReviewerSeesThePreservedEvidenceAndCurrentOptionsAfterExpiry() {
        var other = secondReviewer();
        assertThat(service.detail(other, report).messageRemovable()).isTrue();
        jdbc.update("update tbl_table_group set expires_at=now()-interval '1 second' where id=:id",Map.of("id",table));
        var evidence = service.detail(other,report);
        assertThat(evidence.tableActive()).isFalse();
        assertThat(evidence.messageRemovable()).isFalse();
        assertThat(evidence.messageText()).isEqualTo("selected text");
        review(incident,new Review(Decision.DISMISS,"Reviewed separately",0L));
        assertThat(service.detail(other,report).status()).isEqualTo("OPEN");
        for (var inapplicable : List.of(Decision.REMOVE_MESSAGE,Decision.REMOVE_TABLE)) {
            var rejected = new Review(inapplicable,"Reviewed expiry",0L);
            assertThatThrownBy(() -> transaction.execute(ignored -> service.review(other,report,rejected)))
                    .hasFieldOrPropertyWithValue("errorType",ErrorType.TABLE_GROUP_REPORT_STATE_CHANGED);
        }
        var saved = transaction.execute(ignored -> service.review(other,report,
                new Review(Decision.CONFIRM_VIOLATION,"Original evidence",0L)));
        assertThat(saved.reviewerUsername()).isEqualTo("editor2");
        assertThat(saved.messageText()).isEqualTo("selected text");
    }

    private UserDetailsImpl secondReviewer() {
        var second = UUID.randomUUID();
        jdbc.update("insert into tbl_user(id,user_name) values(:id,'editor2')", Map.of("id",second));
        jdbc.update("insert into user_roles values(:id,:role)", Map.of("id",second,"role",adminRole));
        return UserDetailsImpl.fromUser(User.builder().id(second).username("editor2").build());
    }

    @Test void noteLimitsAndErasedReviewerPreserveTheDecision() {
        for (int length : List.of(4,501)) {
            assertThatThrownBy(() -> review(report,new Review(Decision.DISMISS,"a".repeat(length),0L)))
                    .hasFieldOrPropertyWithValue("errorType",ErrorType.VALIDATION_ERROR);
        }
        var shortNote = review(report,new Review(Decision.DISMISS,"abcde",0L));
        assertThat(shortNote.resolutionNote()).isEqualTo("abcde");
        String longNote = "a".repeat(500);
        var longDecision = review(incident,new Review(Decision.CONFIRM_VIOLATION,longNote,0L));
        assertThat(longDecision.resolutionNote()).isEqualTo(longNote);

        jdbc.update("update tbl_user set erased_at=now() where id=:id",Map.of("id",admin));
        var saved = jdbc.queryForMap("""
                select reviewer_id,reviewer_username,resolution_note,decision,version
                from tbl_table_group_report where id=:id
                """,Map.of("id",incident));
        assertThat(saved).containsEntry("reviewer_id",null).containsEntry("reviewer_username",null)
                .containsEntry("resolution_note",longNote).containsEntry("decision","CONFIRM_VIOLATION")
                .containsEntry("version",1L);
    }

    private Detail review(UUID id,Review request) { return transaction.execute(ignored->service.review(actor,id,request)); }
}
