package com.berkayb.soundconnect.modules.contentreport;

import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
class ReportedProfileSharesPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
    private final UUID owner = UUID.randomUUID(), actor = UUID.randomUUID(), source = UUID.randomUUID(), id = UUID.randomUUID();
    private final CommentTargetAccessGuard visibility = mock(CommentTargetAccessGuard.class);
    private JdbcTemplate jdbc;
    private ReportedProfileShares shares;

    @BeforeEach void fixture() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        shares = new ReportedProfileShares(visibility, jdbc);
        jdbc.execute("""
                drop schema public cascade;
                create schema public;
                create table tbl_user(id uuid primary key);
                create table tbl_event(id uuid primary key, title text);
                create table tbl_event_audience_intent(user_id uuid, event_id uuid, post_id uuid,
                    note text, published_on_profile boolean, published_at timestamptz, updated_at timestamptz, version int, intent text);
                create table tbl_table_group(id uuid primary key, description text, report_removed_at timestamptz);
                create table tbl_table_group_profile_share(id uuid primary key, table_group_id uuid, owner_user_id uuid, note text, final_source jsonb);
                create table tbl_overthinking_post(id uuid primary key, title text);
                create table tbl_overthinking_profile_share(id uuid primary key, source_post_id uuid, owner_user_id uuid, note text);
                """);
        jdbc.update("insert into tbl_user values (?)", owner);
    }

    @Test void removingOneEventPublicationRetainsAttendanceAndRejectsReplacedEvidence() {
        var kind = ContentReportKind.EVENT_PROFILE_SHARE;
        jdbc.update("insert into tbl_event values (?, 'Konser')", source);
        jdbc.update("insert into tbl_event_audience_intent values (?, ?, ?, 'Ön sıra', true, now(), now(), 2, 'GOING')",
                owner, source, id);
        var old = shares.capture(actor, kind, id);
        verify(visibility).requireReadable(actor, EngagementTargetType.EVENT_POST, id);
        assertThat(old.parentText()).isEqualTo("Konser");
        assertThatThrownBy(() -> shares.capture(owner, kind, id)).isInstanceOf(SoundConnectException.class);
        jdbc.update("update tbl_event_audience_intent set note='Balkon' where post_id=?", id);
        assertThat(shares.removable(kind, id, old)).isFalse();
        assertThatThrownBy(() -> shares.remove(kind, id, old)).isInstanceOf(SoundConnectException.class);
        shares.remove(kind, id, shares.capture(actor, kind, id));
        assertThat(jdbc.queryForObject("select intent from tbl_event_audience_intent where not published_on_profile and post_id is null and note is null and version=3", String.class)).isEqualTo("GOING");
        assertThat(shares.removable(kind, id, old)).isFalse();
    }

    @Test void tableRemovalRedactsModeratedSourceAndPreservesOtherPublications() {
        var kind = ContentReportKind.TABLEGROUP_PROFILE_SHARE;
        UUID other = UUID.randomUUID();
        jdbc.update("insert into tbl_table_group values (?, 'Eski açıklama', now())", source);
        for (UUID shareId : new UUID[]{id, other}) {
            jdbc.update("insert into tbl_table_group_profile_share values (?, ?, ?, 'Not', null)", shareId, source, owner);
        }
        var report = shares.capture(actor, kind, id);
        verify(visibility).requireReadable(actor, EngagementTargetType.TABLE_GROUP_POST, id);
        assertThat(report.parentText()).isEqualTo("Bu masa yayından kaldırıldı.");
        shares.remove(kind, id, report);
        assertThat(shares.removable(kind, id, report)).isFalse();
        assertThat(shares.removable(kind, other, report)).isTrue();
    }

    @Test void removingAnOverthinkingSharePreservesOriginalPost() {
        var kind = ContentReportKind.OVERTHINKING_PROFILE_SHARE;
        jdbc.update("insert into tbl_overthinking_post values (?, 'Özgün yazı')", source);
        jdbc.update("insert into tbl_overthinking_profile_share values (?, ?, ?, null)", id, source, owner);
        var report = shares.capture(actor, kind, id);
        verify(visibility).requireReadable(actor, EngagementTargetType.OVERTHINKING_PROFILE_SHARE, id);
        assertThat(report.evidenceText()).isEqualTo("Paylaşım notu yok.");
        shares.remove(kind, id, report);
        assertThat(shares.removable(kind, id, report)).isFalse();
        assertThat(jdbc.queryForObject("select title from tbl_overthinking_post where id=?", String.class, source)).isEqualTo("Özgün yazı");
    }
}
