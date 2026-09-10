package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameLifecycleService;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit listener data inventory. Missing schema or failed cleanup rolls back the entire account erasure. */
@Component
@RequiredArgsConstructor
public class ListenerAccountDataCleaner {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final TableGroupRepository tables;
    private final TableGroupGameLifecycleService games;

    @Transactional(propagation = Propagation.MANDATORY)
    public void prepareLifecycle(UUID userId) {
        eraseTables(userId);
        eraseReservations(userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(UUID userId, UUID profileId) {
        // Source tables are retained as terminal aggregates after owner erasure.
        // Delete both authored shares and other listeners' shares of owned tables;
        // the publication trigger purges associated engagement in this transaction.
        jdbc.update("delete from tbl_table_group_profile_share where owner_user_id = ? or table_group_id in (select id from tbl_table_group where owner_id = ?)", userId, userId);
        purgeTargets("OVERTHINKING", ids("select id from tbl_overthinking_post where author_id = ? order by id for update", userId));
        purgeTargets("EVENT_POST", ids("select post_id from tbl_event_audience_intent where user_id = ? and post_id is not null for update", userId));
        jdbc.update("delete from tbl_like where user_id = ? or (target_type = 'COMMENT' and target_id in (select id from tbl_comment where user_id = ?))", userId, userId);
        jdbc.update("update tbl_comment set text = '[Silinmiş yorum]', is_deleted = true where user_id = ?", userId);
        jdbc.update("delete from tbl_overthinking_reveal_request where author_id = ? or requester_id = ?", userId, userId);
        jdbc.update("delete from tbl_overthinking_profile_share where owner_user_id = ? or source_post_id in (select id from tbl_overthinking_post where author_id = ?)", userId, userId);
        jdbc.update("delete from tbl_overthinking_post where author_id = ?", userId);
        jdbc.update("delete from tbl_overthinking_create_receipt where owner_user_id = ?", userId);
        jdbc.update("delete from tbl_overthinking_reveal_attempt where requester_id = ? or author_id = ?", userId, userId);
        jdbc.update("delete from tbl_overthinking_reveal_inbox where author_id = ?", userId);
        jdbc.update("delete from tbl_event_audience_intent where user_id = ?", userId);
        jdbc.update("delete from tbl_follow where follower_id = ? or following_id = ?", userId, userId);
        jdbc.update("delete from tbl_band_follow where follower_id = ?", userId);
        jdbc.update("delete from tbl_collab_saved_listing where user_id = ?", userId);
        jdbc.update("delete from tbl_venue_applications where user_id = ?", userId);
        jdbc.update("delete from tbl_studio_applications where applicant_id = ?", userId);
        // Receipt tombstones remain: already delivered broker messages must never resurrect an erased snapshot.
        for (String table : List.of("tbl_notification", "tbl_overthinking_notification_outbox",
                "tbl_table_group_notification_outbox", "tbl_collab_notification_outbox", "tbl_event_performer_notification_outbox")) {
            jdbc.update("delete from " + table + " where recipient_id = ? or payload::text like ?", userId, "%" + userId + "%");
        }
        if (profileId != null) {
            jdbc.update("delete from tbl_listener_spotify_playlist where listener_profile_id = ?", profileId);
            jdbc.update("delete from \"tbl_listener-profile\" where id = ?", profileId);
            jdbc.update("update tbl_media_asset set title = null, description = null where owner_type = 'LISTENER_PROFILE' and owner_id = ?", profileId);
        }
        jdbc.update("update tbl_media_asset set title = null, description = null where owner_type = 'USER' and owner_id = ?", userId);
    }

    private void purgeTargets(String type, List<UUID> targets) {
        if (targets.isEmpty()) return;
        var sql = new NamedParameterJdbcTemplate(jdbc);
        // Chunking bounds PostgreSQL bind counts for long-lived accounts.
        for (int offset = 0; offset < targets.size(); offset += 250) {
            Map<String, Object> parameters = Map.of("type", type, "ids", targets.subList(offset, Math.min(offset + 250, targets.size())));
            sql.update("delete from tbl_like where (target_type = :type and target_id in (:ids)) or (target_type = 'COMMENT' and target_id in (select id from tbl_comment where target_type = :type and target_id in (:ids)))", parameters);
            sql.update("delete from tbl_comment where target_type = :type and target_id in (:ids) and parent_comment_id is not null", parameters);
            sql.update("delete from tbl_comment where target_type = :type and target_id in (:ids)", parameters);
        }
    }

    private void eraseTables(UUID userId) {
        List<UUID> tableIds = ids("select id from tbl_table_group where owner_id = ? or id in (select table_group_id from tbl_table_group_participants where user_id = ?) order by id", userId, userId);
        for (UUID tableId : tableIds) {
            tables.findByIdForUpdate(tableId).ifPresent(table -> {
                if (userId.equals(table.getOwnerId())) {
                    games.ownerErased(table);
                    table.setStatus(TableGroupStatus.CANCELLED);
                    table.setDescription("[Silinmiş hesap]");
                    table.getParticipants().clear();
                } else {
                    games.participantErased(table, userId);
                    table.getParticipants().removeIf(participant -> userId.equals(participant.getUserId()));
                }
            });
        }
        entityManager.flush();
        jdbc.update("delete from tbl_table_group_message where table_group_id in (select id from tbl_table_group where owner_id = ?)", userId);
        jdbc.update("update tbl_table_group_game_player set username = 'Silinmiş hesap' where user_id = ?", userId);
        jdbc.update("update tbl_table_group_game set created_by_username = 'Silinmiş hesap' where created_by = ?", userId);
        jdbc.update("update tbl_table_group_game set selected_username = 'Silinmiş hesap', result_message = 'Silinmiş hesap' where selected_user_id = ?", userId);
        // Shared chat history stays, but generated game-card text can contain an old name snapshot.
        jdbc.update("update tbl_table_group_message set content = '[Silinmiş hesap]' where game_id in (select game_id from tbl_table_group_game_player where user_id = ?)", userId);
    }

    private void eraseReservations(UUID userId) {
        // Match the booking aggregate lock order before releasing availability.
        ids("select id from tbl_studio_room where id in (select room_id from tbl_studio_room_reservation where requester_id = ?) order by id for update", userId);
        ids("select id from tbl_studio_room_reservation where requester_id = ? order by id for update", userId);
        jdbc.update("update tbl_studio_room_occupancy set active = false, released_at = now(), released_by = ?, release_reason = 'Account deleted', version = version + 1 where active and reservation_id in (select id from tbl_studio_room_reservation where requester_id = ?)", userId, userId);
        jdbc.update("update tbl_studio_room_reservation set status = 'CANCELLED_BY_CUSTOMER', cancelled_at = now(), cancelled_by = ?, version = version + 1 where requester_id = ? and status in ('PENDING_APPROVAL','CONFIRMED')", userId, userId);
        jdbc.update("update tbl_studio_room_reservation set contact_phone_snapshot = null where requester_id = ?", userId);
    }

    private List<UUID> ids(String query, Object... parameters) { return jdbc.queryForList(query, UUID.class, parameters); }
}
