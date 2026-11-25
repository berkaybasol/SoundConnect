package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor;
import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.user.block.UserBlockService;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Testcontainers
class TableGroupBlockWarningsPostgresTest {
    @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16.4-alpine");
    JdbcTemplate sql;
    TableGroupBlockWarnings warnings;
    SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    TableGroupChatUnreadHelper unread = mock(TableGroupChatUnreadHelper.class);
    UUID table = UUID.randomUUID(), deniz = UUID.randomUUID(), eren = UUID.randomUUID(), ece = UUID.randomUUID();
    UUID application = UUID.randomUUID();

    @BeforeEach void fixture() throws Exception {
        var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        sql = new JdbcTemplate(source);
        sql.execute("""
                create table if not exists tbl_table_group(id uuid primary key, status text, expires_at timestamptz);
                create table if not exists tbl_table_group_participants(table_group_id uuid, user_id uuid, status text,
                        joined_at timestamptz, application_id uuid);
                create table if not exists tbl_user_block(blocker_id uuid, blocked_id uuid, created_at timestamptz, unblocked_at timestamptz);
                truncate tbl_user_block, tbl_table_group_participants, tbl_table_group;
                """);
        sql.execute(Files.readString(Path.of("scripts/db/2026-11-25-table-block-arrival.sql")));
        sql.update("insert into tbl_table_group values (?,'ACTIVE',now()+interval '1 hour')", table);
        for (UUID user : List.of(deniz, ece)) {
            sql.update("""
                    insert into tbl_table_group_participants(table_group_id,user_id,status,joined_at)
                    values (?,?,'ACCEPTED',now()-interval '2 hours')
                    """, table, user);
        }
        sql.update("""
                insert into tbl_table_group_participants(table_group_id,user_id,status,joined_at,application_id)
                values (?,?,'PENDING',now()-interval '3 hours',?)
                """, table, eren, application);
        sql.update("insert into tbl_user_block values (?,?,now()-interval '1 hour',null)", deniz, eren);
        var deliveries = mock(AfterCommitDeliveryExecutor.class);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; }).when(deliveries).submit(any());
        warnings = new TableGroupBlockWarnings(new NamedParameterJdbcTemplate(source), deliveries, messaging, unread);
    }

    @Test void onlyOriginalBlockerGetsLiveAndRecoveredArrivalForCurrentAcceptedCycle() {
        assertThat(warnings.forViewer(table, deniz)).isEmpty();
        warnings.notifyAccepted(table, eren, application);
        verifyNoInteractions(messaging);
        sql.update("update tbl_table_group_participants set status='ACCEPTED',accepted_at=now() where user_id=?", eren);
        assertThat(warnings.forViewer(table, deniz)).containsExactlyEntriesOf(Map.of(eren,true));
        assertThat(warnings.forViewer(table, ece)).isEmpty();
        assertThat(warnings.forViewer(table, eren)).isEmpty();
        warnings.notifyAccepted(table, eren, UUID.randomUUID());
        verifyNoInteractions(messaging);
        warnings.notifyAccepted(table, eren, application);
        verify(messaging).convertAndSend(WebSocketChannels.tableGroup(table, deniz),
                Map.of("type","BLOCKED_MEMBER_JOINED","tableGroupId",table,"userId",eren,"applicationId",application));
        verifyNoMoreInteractions(messaging);

        sql.update("update tbl_user_block set created_at=now()+interval '1 second'");
        assertThat(warnings.forViewer(table, deniz)).containsEntry(eren,false);
        sql.update("update tbl_user_block set unblocked_at=now()");
        assertThat(warnings.forViewer(table, deniz)).isEmpty();
    }

    @Test void nonmemberCanConfirmBeforeRequestAndOnlySharedMembersReceiveInvalidation() {
        sql.update("update tbl_table_group_participants set status='ACCEPTED',accepted_at=now() where user_id=?", eren);
        sql.update("update tbl_table_group_participants set status='LEFT' where user_id=?", deniz);
        assertThat(warnings.forViewer(table, deniz)).containsEntry(eren,false);
        warnings.relationshipChanged(new UserBlockService.RelationshipChanged(deniz, eren, true));
        verifyNoInteractions(messaging, unread);
        // Eren already occupied his seat when Deniz submitted and acknowledged this application.
        sql.update("""
                update tbl_table_group_participants set status='ACCEPTED',joined_at=now()+interval '1 minute',
                    accepted_at=now()+interval '2 minutes' where user_id=?
                """, deniz);
        assertThat(warnings.forViewer(table, deniz)).containsEntry(eren,false);
        warnings.relationshipChanged(new UserBlockService.RelationshipChanged(deniz, eren, true));
        for (UUID viewer : List.of(deniz, eren)) {
            verify(messaging).convertAndSend(WebSocketChannels.tableGroup(table,viewer),
                    Map.of("type","TABLE_VISIBILITY_CHANGED","tableGroupId",table));
            verify(unread).resetUnread(viewer,table);
        }
        verifyNoMoreInteractions(messaging, unread);
    }

    @Test void blockerLearnsAboutAnArrivalDuringTheirPendingApplicationOnFirstAcceptedRead() {
        sql.update("update tbl_user_block set created_at=now()-interval '4 hours'");
        sql.update("update tbl_table_group_participants set status='PENDING' where user_id=?", deniz);
        assertThat(warnings.forViewer(table, deniz)).isEmpty();
        sql.update("update tbl_table_group_participants set status='ACCEPTED',accepted_at=now()-interval '30 minutes' where user_id=?", eren);
        warnings.notifyAccepted(table, eren, application);
        verifyNoInteractions(messaging);
        assertThat(warnings.forViewer(table, deniz)).containsEntry(eren,false);

        sql.update("update tbl_table_group_participants set status='ACCEPTED',accepted_at=now()-interval '5 minutes' where user_id=?", deniz);
        assertThat(warnings.forViewer(table, deniz)).containsExactlyEntriesOf(Map.of(eren,true));
        assertThat(warnings.forViewer(table, eren)).isEmpty();
        assertThat(warnings.forViewer(table, ece)).isEmpty();

        // A new block after both seats were occupied is the user's own in-table action.
        sql.update("update tbl_user_block set created_at=now()");
        assertThat(warnings.forViewer(table, deniz)).containsEntry(eren,false);
    }

    @Test void oldSeatNewlyBlockedDuringThePendingRequestIsAlsoShownOnApproval() {
        sql.update("""
                update tbl_table_group_participants set status='ACCEPTED',accepted_at=now()-interval '3 hours'
                where user_id=?
                """, eren);
        // The fixture's block was created one hour ago, between application and acceptance.
        sql.update("update tbl_table_group_participants set accepted_at=now() where user_id=?", deniz);
        assertThat(warnings.forViewer(table, deniz)).containsEntry(eren,true);
        assertThat(warnings.forViewer(table, eren)).isEmpty();
        assertThat(warnings.forViewer(table, ece)).isEmpty();
    }

    @Test void ownerWhoseSeatStartedAtCreationIsWarnedOnlyForSubsequentArrivals() {
        sql.update("update tbl_table_group_participants set accepted_at=joined_at where user_id=?", deniz);
        sql.update("update tbl_table_group_participants set status='ACCEPTED',accepted_at=now()-interval '30 minutes' where user_id=?", eren);
        assertThat(warnings.forViewer(table, deniz)).containsEntry(eren,true);
        sql.update("update tbl_user_block set created_at=now()");
        assertThat(warnings.forViewer(table, deniz)).containsEntry(eren,false);
    }
}
