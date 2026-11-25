package com.berkayb.soundconnect.modules.contentreport;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.berkayb.soundconnect.modules.contentreport.ContentReportKind.*;
import static com.berkayb.soundconnect.modules.contentreport.ContentReportModeration.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class ContentReportPostgresTest {
    @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16.4-alpine");
    static NamedParameterJdbcTemplate jdbc;
    static TransactionTemplate tx;
    static UUID role = UUID.randomUUID();
    final UserDetailsImpl reporter = user("reporter"), admin = user("editor");
    final UUID target = UUID.randomUUID();
    final ContentReportTargets targets = mock(ContentReportTargets.class);
    ContentReportService reports;
    ContentReportModeration moderation;

    @BeforeAll static void install() throws Exception {
        var ds = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.getJdbcTemplate().execute("""
                create table tbl_user(id uuid primary key,user_name text,status text default 'ACTIVE',
                    email_verified boolean default true,erased_at timestamptz,session_version bigint default 0,
                    access_suspended boolean default false);
                create table tbl_permissions(id uuid primary key,name text unique,created_at timestamp,updated_at timestamp);
                create table tbl_role(id uuid primary key,name text);
                create table role_permissions(role_id uuid,permission_id uuid,primary key(role_id,permission_id));
                create table user_permissions(user_id uuid,permission_id uuid);
                create table user_roles(user_id uuid,role_id uuid);
                create table "tbl_listener-profile"(user_id uuid);
                """);
        jdbc.update("insert into tbl_role values(:id,'ROLE_ADMIN')", Map.of("id",role));
        jdbc.getJdbcTemplate().execute("insert into tbl_permissions(id,name) values(gen_random_uuid(),'ADMIN_PANEL_ACCESS')");
        jdbc.update("insert into role_permissions select :id,id from tbl_permissions",Map.of("id",role));
        String migration = Files.readString(Path.of("scripts/db/2026-11-25-content-reports.sql"));
        jdbc.getJdbcTemplate().execute(migration);
        jdbc.getJdbcTemplate().execute(migration);
    }

    @BeforeEach void setup() {
        addUser(reporter);
        addUser(admin);
        jdbc.update("insert into user_roles values(:id,:role)", Map.of("id",admin.getId(),"role",role));
        var fence = new AccountDeliveryFence(jdbc);
        reports = new ContentReportService(jdbc, fence, targets);
        moderation = new ContentReportModeration(jdbc, new AdminPanelReadAccess(jdbc), fence, targets);
    }

    @Test void exactRetryAndImmutableSnapshotSurviveSourceRemoval() {
        var request = request(COMMENT);
        var evidence = new ContentReportEvidence("Yanıt", "selected reply", "parent context");
        when(targets.capture(reporter.getId(), COMMENT, target,null)).thenReturn(evidence);
        when(targets.removable(COMMENT,target,evidence)).thenReturn(true);
        var first = submit(request);
        assertThat(submit(request)).isEqualTo(first);
        verify(targets).capture(reporter.getId(),COMMENT,target,null);
        assertThatThrownBy(() -> submit(new ContentReportRequest(request.requestId(), COMMENT,target,null,
                ContentReportRequest.Reason.OTHER,"different note")))
                .hasFieldOrPropertyWithValue("errorType", ErrorType.CONTENT_REPORT_STATE_CHANGED);
        assertThat(moderation.list(admin,Status.OPEN,COMMENT,0,50).content()).extracting(Summary::id).contains(first.id());
        var detail = moderation.detail(admin,first.id());
        assertThat(detail.profileUserId()).isNull();
        assertThat(detail.parentText()).isEqualTo("parent context");
        assertThat(detail.description()).isEqualTo("reported item");
        var decision = new Review(Decision.REMOVE_CONTENT,"Reviewed evidence",0L);
        var saved = review(first.id(),decision);
        assertThat(saved.evidenceText()).isEqualTo("selected reply");
        assertThat(saved.removable()).isFalse();
        assertThat(saved.reviewerUsername()).isEqualTo("editor");
        var wrongVersion = new Review(decision.decision(), decision.resolutionNote(), saved.version());
        assertThatThrownBy(() -> review(first.id(), wrongVersion))
                .hasFieldOrPropertyWithValue("errorType", ErrorType.CONTENT_REPORT_STATE_CHANGED);
        assertThat(review(first.id(),decision)).isEqualTo(saved);
        verify(targets).remove(COMMENT,target,evidence);
        assertThatThrownBy(() -> review(first.id(), new Review(Decision.DISMISS,"Another decision",0L)))
                .hasFieldOrPropertyWithValue("errorType",ErrorType.CONTENT_REPORT_STATE_CHANGED);
    }

    @Test void targetFailureAndRevokedPermissionLeaveDecisionOpen() {
        var evidence = new ContentReportEvidence("original", "body", null);
        when(targets.capture(reporter.getId(), OVERTHINKING_POST,target,null)).thenReturn(evidence);
        var id = submit(request(OVERTHINKING_POST)).id();
        doThrow(new SoundConnectException(ErrorType.CONTENT_REPORT_STATE_CHANGED))
                .when(targets).remove(OVERTHINKING_POST,target,evidence);
        assertThatThrownBy(() -> review(id,new Review(Decision.REMOVE_CONTENT,"Current source changed",0L)))
                .hasFieldOrPropertyWithValue("errorType", ErrorType.CONTENT_REPORT_STATE_CHANGED);
        assertThat(moderation.detail(admin,id).status()).isEqualTo(Status.OPEN);
        jdbc.update("delete from user_roles where user_id=:id",Map.of("id",admin.getId()));
        assertThatThrownBy(() -> review(id,new Review(Decision.DISMISS,"Checked evidence",0L)))
                .hasFieldOrPropertyWithValue("errorType",ErrorType.FORBIDDEN_ACCESS);
        assertThatThrownBy(() -> moderation.detail(admin,id)).hasFieldOrPropertyWithValue("errorType",ErrorType.FORBIDDEN_ACCESS);
    }

    @Test void profileIsAccountOnlyAndErasureUnlinksPersonalData() {
        jdbc.update("insert into tbl_user(id,user_name) values(:id,'owner')", Map.of("id",target));
        UUID venue = UUID.randomUUID();
        var request = new ContentReportRequest(UUID.randomUUID(),VENUE_PROFILE,target,venue,ContentReportRequest.Reason.OTHER,null);
        when(targets.capture(reporter.getId(),VENUE_PROFILE,target,venue))
                .thenReturn(new ContentReportEvidence("Selected venue","Public biography",null));
        var id = submit(request).id();
        assertThat(moderation.detail(admin,id).profileUserId()).isEqualTo(target);
        assertThatThrownBy(()->review(id,new Review(Decision.REMOVE_CONTENT,"Review profile",0L)))
                .hasFieldOrPropertyWithValue("errorType",ErrorType.VALIDATION_ERROR);
        verify(targets,never()).remove(any(),any(),any());
        jdbc.update("update tbl_user set erased_at=now() where id in (:ids)", Map.of("ids",List.of(target,reporter.getId())));
        var erased = moderation.detail(admin,id);
        assertThat(erased.reporterUsername()).isNull();
        assertThat(erased.profileUserId()).isNull();
        assertThat(erased.evidenceText()).isNull();
        assertThat(erased.targetLabel()).isEqualTo("Silinmiş profil");
        assertThat(review(id,new Review(Decision.CONFIRM_VIOLATION,"Reviewed evidence",0L)).version()).isEqualTo(1L);
    }

    @Test void simultaneousSubmissionsAndDecisionsProduceOnlyOneRecordAndOneDecision() throws Exception {
        var request = request(COMMENT);
        when(targets.capture(reporter.getId(),COMMENT,target,null)).thenReturn(new ContentReportEvidence("Yorum","text",null));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return submit(request); });
            var second = pool.submit(() -> { start.await(); return submit(request); });
            start.countDown();
            var receipt = first.get(10,TimeUnit.SECONDS);
            assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo(receipt);
            var draw = new CountDownLatch(1);
            var dismiss = pool.submit(() -> win(draw,receipt.id(),Decision.DISMISS));
            var uphold = pool.submit(() -> win(draw,receipt.id(),Decision.CONFIRM_VIOLATION));
            draw.countDown();
            assertThat(List.of(dismiss.get(10,TimeUnit.SECONDS),uphold.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(false,true);
        }
    }

    @Test void hourlyLimitDoesNotBlockReceiptReplayOrDependOnEditsToSource() {
        when(targets.capture(reporter.getId(),COMMENT,target,null)).thenReturn(new ContentReportEvidence("Yorum","body",null));
        var request = request(COMMENT);
        var receipt = submit(request);
        for (int i = 1; i < 30; i++) submit(request(COMMENT));
        assertThatThrownBy(() -> submit(request(COMMENT))).hasFieldOrPropertyWithValue("errorType",ErrorType.CONTENT_REPORT_RATE_LIMITED);
        assertThat(submit(request)).isEqualTo(receipt);
    }

    @Test void inactiveReporterAndInvisibleTargetCannotSubmit() {
        var request = request(COMMENT);
        when(targets.capture(reporter.getId(),COMMENT,target,null)).thenThrow(new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND));
        assertThatThrownBy(() -> submit(request)).hasFieldOrPropertyWithValue("errorType",ErrorType.CONTENT_REPORT_TARGET_UNAVAILABLE);
        jdbc.update("update tbl_user set session_version=1 where id=:id", Map.of("id",reporter.getId()));
        assertThatThrownBy(() -> submit(request)).hasFieldOrPropertyWithValue("errorType",ErrorType.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("select count(*) from tbl_content_report where reporter_id=:id",
                Map.of("id",reporter.getId()),Long.class)).isZero();
    }

    private boolean win(CountDownLatch gate, UUID id, Decision decision) throws InterruptedException {
        gate.await();
        try {
            review(id,new Review(decision,"Checked evidence",0L));
            return true;
        } catch (SoundConnectException failure) {
            assertThat(failure.getErrorType()).isEqualTo(ErrorType.CONTENT_REPORT_STATE_CHANGED);
            return false;
        }
    }

    private static UserDetailsImpl user(String name) {
        return UserDetailsImpl.fromUser(User.builder().id(UUID.randomUUID()).username(name).build());
    }
    private static void addUser(UserDetailsImpl user) {
        jdbc.update("insert into tbl_user(id,user_name) values(:id,:name)",Map.of("id",user.getId(),"name",user.getUsername()));
    }
    private ContentReportRequest request(ContentReportKind kind) {
        return new ContentReportRequest(UUID.randomUUID(),kind,target,null,ContentReportRequest.Reason.SPAM,"  reported item  ");
    }
    private ContentReportService.Receipt submit(ContentReportRequest request) {
        return tx.execute(status -> reports.create(reporter,request));
    }
    private Detail review(UUID id, Review decision) {
        return tx.execute(status -> moderation.review(admin,id,decision));
    }
}
