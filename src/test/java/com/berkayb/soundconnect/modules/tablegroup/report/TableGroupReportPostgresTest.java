package com.berkayb.soundconnect.modules.tablegroup.report;

import com.berkayb.soundconnect.modules.tablegroup.abuse.TableGroupRateLimitGuard;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.berkayb.soundconnect.modules.tablegroup.report.TableGroupReportRequest.Kind.*;
import static com.berkayb.soundconnect.modules.tablegroup.report.TableGroupReportRequest.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class TableGroupReportPostgresTest {
    @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16.4-alpine");
    static JdbcTemplate sql;
    static RecentReportableTables recent;
    static TransactionTemplate tx;
    TableGroupReportService reports;
    TableGroupRateLimitGuard limits;
    UUID owner, actor, outside, table, message;

    @BeforeAll static void setup() throws Exception {
        var ds = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        sql = new JdbcTemplate(ds);
        sql.execute("""
                create table tbl_user(id uuid primary key,user_name text,erased_at timestamp,status text default 'ACTIVE',
                    access_suspended boolean default false,email_verified boolean default true);
                create table tbl_city(id uuid primary key,name text);
                create table tbl_district(id uuid primary key,name text);
                create table tbl_table_group(id uuid primary key, owner_id uuid, city_id uuid, district_id uuid,
                    description text, venue_name text, meeting_at timestamptz,expires_at timestamptz,status text);
                create table tbl_table_group_message(id uuid primary key,table_group_id uuid,sender_id uuid,content text,
                    message_type text,created_at timestamp default (now() at time zone 'UTC'),deleted_at timestamp);
                create table tbl_table_group_participants(table_group_id uuid,user_id uuid,status text,joined_at timestamptz);
                create table tbl_table_notification_event(recipient_id uuid, payload jsonb, occurred_at timestamptz, notification_type text);
                """);
        String migration = Files.readString(Path.of("scripts/db/2026-11-24-table-group-reports.sql"));
        sql.execute(migration);
        sql.execute(migration);
        recent = new RecentReportableTables(new NamedParameterJdbcTemplate(ds));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @BeforeEach void fixture() {
        limits = mock(TableGroupRateLimitGuard.class);
        var jdbc = new NamedParameterJdbcTemplate(sql);
        reports = new TableGroupReportService(jdbc, recent, new AccountDeliveryFence(jdbc), limits);
        owner = user("masa"); actor = user("uye"); outside = user("diger");
        UUID city = UUID.randomUUID(); table = UUID.randomUUID(); message = UUID.randomUUID();
        sql.update("insert into tbl_city values (?, 'İstanbul')", city);
        sql.update("""
                insert into tbl_table_group(id,owner_id,city_id,description,meeting_at,expires_at,status)
                values(?,?,?,'Konser öncesi',now()+interval '1 hour',now()+interval '1 day','ACTIVE')
                """, table,owner,city);
        sql.update("insert into tbl_table_group_participants values(?,?,'ACCEPTED',now())",table,actor);
        sql.update("insert into tbl_table_group_message(id,table_group_id,sender_id,content,message_type) values(?,?,?,'Seçilen metin','TEXT')",message,table,owner);
    }

    private UUID user(String name) {
        UUID id=UUID.randomUUID();
        sql.update("insert into tbl_user(id,user_name) values (?,?)",id,name);
        return id;
    }
    private TableGroupReportService.Receipt send(UUID who, TableGroupReportRequest req) {
        return tx.execute(status -> reports.create(who, table, req));
    }
    private TableGroupReportRequest incident() {
        return new TableGroupReportRequest(UUID.randomUUID(),INCIDENT,SAFETY,"Buluşmada yaşadığım olay.",null);
    }
    private TableGroupReportRequest message(UUID request) {
        return new TableGroupReportRequest(request,MESSAGE,MONEY_REQUEST,"  Beyanım  ",message);
    }
    private void denied(UUID who, TableGroupReportRequest req, ErrorType type) {
        assertThatThrownBy(() -> send(who,req)).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(type));
    }

    @Test void selectedMessageAndExactReplaySurviveCancellation() throws Exception {
        var req=message(UUID.randomUUID());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(() -> send(actor,req));
            var two=pool.submit(() -> send(actor,req));
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(two.get(10, TimeUnit.SECONDS));
        }
        sql.update("update tbl_table_group set status='CANCELLED' where id=?",table);
        sql.update("delete from tbl_table_group_message where table_group_id=?",table);
        var replay=send(actor,req);
        var saved=sql.queryForMap("select * from tbl_table_group_report where id=?",replay.id());
        assertThat(saved).containsEntry("message_text","Seçilen metin").containsEntry("reporter_username","uye")
                .containsEntry("sender_username","masa").containsEntry("description","Beyanım");
        verify(limits, times(1)).checkReport(actor);
        denied(actor,new TableGroupReportRequest(req.requestId(),MESSAGE,MONEY_REQUEST,"Farklı",message),
                ErrorType.TABLE_GROUP_REPORT_REQUEST_CONFLICT);
        denied(actor,message(UUID.randomUUID()),ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
    }

    @Test void visibleListingAndPrivateMessageHaveIndependentRights() {
        var listing = new TableGroupReportRequest(UUID.randomUUID(),LISTING,MISLEADING,null,null);
        assertThat(send(outside,listing).id()).isNotNull();
        denied(outside,message(UUID.randomUUID()),ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
        denied(outside,incident(),ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
        denied(owner,message(UUID.randomUUID()),ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
        denied(actor,new TableGroupReportRequest(UUID.randomUUID(),INCIDENT,SAFETY," \u00a0\ufeff",null),ErrorType.VALIDATION_ERROR);
        denied(actor,new TableGroupReportRequest(UUID.randomUUID(),MESSAGE,SAFETY,"x",message),ErrorType.VALIDATION_ERROR);
        denied(actor,new TableGroupReportRequest(UUID.randomUUID(),LISTING,OTHER,"x".repeat(501),null),ErrorType.VALIDATION_ERROR);
        denied(actor,new TableGroupReportRequest(UUID.randomUUID(),MESSAGE,SPAM,null,UUID.randomUUID()),ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
        assertThat(send(actor,incident()).id()).isNotNull();
        assertThat(send(actor,incident()).id()).isNotNull();
        sql.update("update tbl_table_group_message set deleted_at=now() where id=?",message);
        denied(actor,message(UUID.randomUUID()),ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
    }

    @Test void bothParticipantsCanSelectOnlyAnotherPersonsTextAndTheEvidenceKeepsItsOriginalTime() {
        var before = UUID.randomUUID();
        var after = UUID.randomUUID();
        var event = UUID.randomUUID();
        var game = UUID.randomUUID();
        var sentAt = Instant.parse("2026-11-23T10:30:00Z");
        sql.update("update tbl_table_group_message set created_at=cast(? as timestamptz) at time zone 'UTC' where id=?",
                Timestamp.from(sentAt), message);
        sql.update("""
                insert into tbl_table_group_message(id,table_group_id,sender_id,content,message_type,created_at)
                values(?,?,?,'Before selected','TEXT','2026-11-23T10:29:00'),
                      (?,?,?,'After selected','TEXT','2026-11-23T10:31:00'),
                      (?,?,?,'Member notification','SYSTEM','2026-11-23T10:32:00'),
                      (?,?,?,'Game result','GAME','2026-11-23T10:33:00')
                """,before,table,actor,after,table,actor,event,table,owner,game,table,owner);
        for (var target : java.util.List.of(event,game)) {
            denied(actor,new TableGroupReportRequest(UUID.randomUUID(),MESSAGE,OTHER,null,target),
                    ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
        }
        var selected = send(actor,message(UUID.randomUUID())).id();
        var saved = sql.queryForMap("select * from tbl_table_group_report where id=?",selected);
        assertThat(saved).containsEntry("message_id",message).containsEntry("message_text","Seçilen metin")
                .containsEntry("sender_id",owner).containsEntry("reporter_id",actor);
        assertThat(((Timestamp)saved.get("message_sent_at")).toInstant()).isEqualTo(sentAt);
        assertThat(saved.values()).doesNotContain("Before selected","After selected","Member notification","Game result");
        var reply = new TableGroupReportRequest(UUID.randomUUID(),MESSAGE,OTHER,null,after);
        var ownerReport = send(owner,reply).id();
        assertThat(sql.queryForMap("select sender_id,message_id,message_text,reporter_id from tbl_table_group_report where id=?",
                ownerReport)).containsEntry("sender_id",actor).containsEntry("message_id",after)
                .containsEntry("message_text","After selected").containsEntry("reporter_id",owner);
    }

    @Test void departedMemberCanReportAfterReapplyingWithoutReadingChat() {
        sql.update("update tbl_table_group_participants set status='PENDING',joined_at=now() where table_group_id=?",table);
        sql.update("""
                insert into tbl_table_notification_event values(?,jsonb_build_object('module','TABLE','action','PARTICIPANT_LEFT',
                    'tableGroupId',cast(? as text),'leaverId',cast(? as text)),now()-interval '47 hours','TABLE_PARTICIPANT_LEFT')
                """,owner,table,actor);
        assertThat(recent.list(actor,0,20).content()).singleElement().satisfies(row -> {
            assertThat(row.endReason()).isEqualTo("LEFT");
            assertThat(row.reportUntil()).isEqualTo(row.endedAt().plus(RecentReportableTables.WINDOW));
        });
        assertThat(recent.list(outside,0,20).content()).isEmpty();
        assertThat(recent.list(owner,0,20).content()).isEmpty();
        var id=send(actor,incident()).id();
        assertThat(sql.queryForObject("select membership_end_reason from tbl_table_group_report where id=?",String.class,id)).isEqualTo("LEFT");
        denied(actor,message(UUID.randomUUID()),ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
        sql.update("update tbl_table_notification_event set occurred_at=now()-interval '48 hours' where recipient_id=?",owner);
        assertThat(recent.list(actor,0,20).content()).isEmpty();
        denied(actor,incident(),ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
    }

    @Test void removedMemberRetainsOnlyOwnWindowEvenAfterParticipantPruning() {
        sql.update("delete from tbl_table_group_participants where table_group_id=?",table);
        sql.update("""
                insert into tbl_table_notification_event values(?,jsonb_build_object('module','TABLE','action','PARTICIPANT_REMOVED',
                    'tableGroupId',cast(? as text)),now()-interval '2 hours','TABLE_REMOVED')
                """,actor,table);
        assertThat(recent.list(actor,0,20).content()).extracting(RecentReportableTables.Row::endReason).containsExactly("KICKED");
        assertThat(recent.list(outside,0,20).content()).isEmpty();
        assertThat(send(actor,incident()).id()).isNotNull();
    }

    @Test void formerMemberListAndSubmissionExpireExactlyAtFortyEightHours() {
        Instant endedAt = Instant.parse("2026-11-01T10:00:00Z");
        Instant deadline = endedAt.plus(RecentReportableTables.WINDOW);
        sql.update("update tbl_table_group_participants set status='LEFT',joined_at=? where table_group_id=?",
                Timestamp.from(endedAt), table);
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            Instant justBefore = deadline.minusMillis(1);
            clock.when(Instant::now).thenReturn(justBefore);
            assertThat(recent.list(actor, 0, 20).content()).singleElement()
                    .satisfies(row -> assertThat(row.reportUntil()).isEqualTo(deadline));
            assertThat(send(actor, incident()).id()).isNotNull();

            clock.when(Instant::now).thenReturn(deadline);
            assertThat(recent.list(actor, 0, 20).content()).isEmpty();
            denied(actor, incident(), ErrorType.TABLE_GROUP_REPORT_TARGET_UNAVAILABLE);
        }
    }

    @Test void cutoffIsLogicalExpiryEvenBeforeSchedulerAndEarlyClosureIsDatedOnce() {
        sql.update("update tbl_table_group set expires_at=now()-interval '1 minute' where id=?",table);
        assertThat(recent.list(owner,0,20).content()).extracting(RecentReportableTables.Row::endReason).containsExactly("EXPIRED");
        assertThat(recent.list(actor,0,20).content()).hasSize(1);
        assertThat(send(owner,incident()).id()).isNotNull();
        sql.update("update tbl_table_group set expires_at=now()+interval '1 hour' where id=?",table);
        sql.update("update tbl_table_group set status='CANCELLED' where id=?",table);
        sql.update("update tbl_table_group set report_removed_at=now() where id=?",table);
        assertThat(recent.list(actor,0,20).content()).singleElement().satisfies(row -> {
            assertThat(row.endReason()).isEqualTo("CLOSED");
            assertThat(row.tableDescription()).isEqualTo("Bu masa yayından kaldırıldı.");
        });
        assertThat(recent.list(outside,0,20).content()).isEmpty();
    }

    @Test void erasureUnlinksNamesInSelectedEvidence() {
        var id=send(actor,message(UUID.randomUUID())).id();
        sql.update("update tbl_user set erased_at=now() where id in (?,?)",actor,owner);
        var row=sql.queryForMap("select reporter_id,reporter_username,owner_id,owner_username,sender_id,sender_username,message_text from tbl_table_group_report where id=?",id);
        assertThat(row).containsEntry("message_text","Seçilen metin");
        row.remove("message_text"); assertThat(row.values()).containsOnlyNulls();
        denied(actor,incident(),ErrorType.ACCOUNT_DELETED);
    }
}
