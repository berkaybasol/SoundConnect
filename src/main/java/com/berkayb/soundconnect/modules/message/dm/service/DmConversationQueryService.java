package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

/** Bounded conversation, identity and latest-message projection for the mobile inbox. */
@Service
@RequiredArgsConstructor
public class DmConversationQueryService {
    private static final String ORDER = " last_message_at desc nulls last, id desc ";
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountDeliveryFence accounts;
    private final GhostListenerIdentityBatchResolver ghostIdentities;
    private final MediaAssetService media;

    // Write-capable transactions are required for the caller-account and
    // listener-visibility SHARE locks used by the shared policy components.
    @Transactional
    public DMConversationPageResponseDto page(UUID actor, int size, String cursorValue) {
        if (size < 1 || size > 100) throw new SoundConnectException(ErrorType.BAD_REQUEST);
        var cursor = DmConversationCursor.decode(actor, cursorValue);
        accounts.requireActive(List.of(actor));
        var params = new MapSqlParameterSource("actor", actor).addValue("limit", size + 1);
        var branches = new ArrayList<String>();
        if (cursor != null) {
            params.addValue("beforeId", cursor.conversationId());
            if (cursor.lastMessageAt() == null) {
                addParticipantBranches(branches, " and last_message_at is null and id < :beforeId ");
            } else {
                params.addValue("beforeAt", cursor.lastMessageAt());
                // Separate the null tail so the timestamp/id tuple is an index
                // range condition, rather than filtering every preceding page.
                addParticipantBranches(branches, " and (last_message_at,id) < (:beforeAt,:beforeId) ");
                addParticipantBranches(branches, " and last_message_at is null ");
            }
        } else {
            addParticipantBranches(branches, "");
        }
        // The canonical participant pair makes the branches disjoint. Each
        // branch uses its own participant/time index before the bounded merge.
        String candidates = "with candidates as (" + String.join(" union all ", branches)
                + "), page as (select * from candidates order by " + ORDER + " limit :limit) ";
        var rows = jdbc.query(candidates + PROJECTION, params, (rs, n) -> row(rs));
        boolean hasNext = rows.size() > size;
        var selected = hasNext ? rows.subList(0, size) : rows;
        String next = hasNext ? DmConversationCursor.encode(actor,
                new DmConversationCursor.Cursor(selected.getLast().sortAt(), selected.getLast().id())) : null;
        return new DMConversationPageResponseDto(project(actor, selected), hasNext, next);
    }

    private static void addParticipantBranches(List<String> branches, String before) {
        for (String participant : List.of("user_a_id", "user_b_id")) {
            branches.add("(select id,user_a_id,user_b_id,last_message_at from tbl_dm_conversation where "
                    + participant + "=:actor " + before + " order by " + ORDER + " limit :limit)");
        }
    }

    /** Membership is checked in SQL, including when a stale push targets an old chat. */
    @Transactional
    public DMConversationPreviewResponseDto preview(UUID actor, UUID conversationId) {
        accounts.requireActive(List.of(actor));
        var rows = jdbc.query("""
                with page as (select id,user_a_id,user_b_id,last_message_at from tbl_dm_conversation
                    where id=:conversation and (user_a_id=:actor or user_b_id=:actor))
                """ + PROJECTION, Map.of("actor", actor, "conversation", conversationId), (rs, n) -> row(rs));
        if (rows.isEmpty()) throw new SoundConnectException(ErrorType.CONVERSATION_NOT_FOUND);
        return project(actor, rows).getFirst();
    }

    private List<DMConversationPreviewResponseDto> project(UUID actor, List<Row> rows) {
        if (rows.isEmpty()) return List.of();
        var peers = rows.stream().map(Row::peer).distinct().toList();
        // This resolver locks listener rows before reading current visibility.
        // Its result always wins over other profiles, including corrupt legacy
        // accounts with both a listener and a professional profile.
        Map<UUID, GhostListenerIdentity> ghosts = ghostIdentities.resolve(peers);
        var erased = new HashSet<>(jdbc.queryForList(
                "select id from tbl_user where id in (:ids) and erased_at is not null", Map.of("ids", peers), UUID.class));
        var mediaIds = new LinkedHashSet<UUID>();
        for (var row : rows) {
            if (row.missingOrErased() || erased.contains(row.peer()) || ghosts.containsKey(row.peer()) || row.restricted()) continue;
            mediaIds.addAll(row.avatars());
        }
        Map<UUID, String> urls = Map.of();
        if (!mediaIds.isEmpty()) {
            // Avatar availability must not make existing shared history unreadable.
            try { urls = media.getDisplayUrlMap(List.copyOf(mediaIds)); } catch (RuntimeException unavailable) { /* omit unavailable avatars */ }
        }
        var result = new ArrayList<DMConversationPreviewResponseDto>(rows.size());
        for (var row : rows) {
            var ghost = ghosts.get(row.peer());
            boolean deleted = row.missingOrErased() || erased.contains(row.peer());
            String name;
            String avatar = null;
            ListenerVisibilityMode visibility = null;
            if (deleted) {
                name = "Silinmiş hesap";
            } else if (ghost != null) {
                name = ghost.username(); avatar = ghost.profilePictureUrl(); visibility = ghost.visibilityMode();
            } else if (row.restricted()) {
                // Defensive fail-closed fallback if a legacy profile was read
                // during a visibility transition. Never use a professional avatar.
                name = row.pending() ? "Kullanici" : firstNonBlank(row.username(), "Kullanici");
                visibility = ListenerVisibilityMode.GHOST;
            } else {
                name = firstNonBlank(row.names());
                for (var id : row.avatars()) {
                    if (urls.get(id) != null) { avatar = urls.get(id); break; }
                }
                if (avatar == null) avatar = row.legacyAvatar();
            }
            Boolean read = row.messageId() == null ? null : !actor.equals(row.recipient()) || row.readAt() != null;
            result.add(new DMConversationPreviewResponseDto(row.id(), row.peer(), name, avatar,
                    row.content(), row.type(), row.sender(), row.messageAt(), read, visibility, deleted));
        }
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) if (value != null && !value.isBlank()) return value;
        return "Bilinmeyen Kullanıcı";
    }

    private static Row row(ResultSet rs) throws SQLException {
        var avatars = new ArrayList<UUID>();
        for (var column : List.of("musician_avatar", "listener_avatar", "organizer_avatar", "producer_avatar", "studio_avatar", "venue_avatar")) {
            UUID value = rs.getObject(column, UUID.class);
            if (value != null) avatars.add(value);
        }
        boolean pending = rs.getBoolean("listener_present") && !rs.getBoolean("visibility_choice_completed");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("peer_id", UUID.class),
                rs.getObject("sort_at", LocalDateTime.class), rs.getBoolean("missing_or_erased"),
                pending, pending || "GHOST".equals(rs.getString("visibility_mode")), rs.getString("username"),
                new String[]{rs.getString("venue_name"), rs.getString("username"), rs.getString("musician_name"),
                        rs.getString("organizer_name"), rs.getString("producer_name"), rs.getString("studio_name"), rs.getString("listener_name")},
                List.copyOf(avatars), rs.getString("legacy_avatar"), rs.getObject("message_id", UUID.class),
                rs.getString("content"), rs.getString("message_type"), rs.getObject("sender_id", UUID.class),
                rs.getObject("recipient_id", UUID.class), rs.getObject("message_at", LocalDateTime.class),
                rs.getObject("read_at", LocalDateTime.class));
    }

    private record Row(UUID id, UUID peer, LocalDateTime sortAt, boolean missingOrErased,
                       boolean pending, boolean restricted, String username, String[] names, List<UUID> avatars,
                       String legacyAvatar, UUID messageId, String content, String type, UUID sender,
                       UUID recipient, LocalDateTime messageAt, LocalDateTime readAt) { }

    private static final String PROJECTION = """
            select p.id, p.last_message_at as sort_at,
                case when p.user_a_id=:actor then p.user_b_id else p.user_a_id end as peer_id,
                (u.id is null or u.erased_at is not null) as missing_or_erased,
                u.user_name as username, u.profile_picture as legacy_avatar,
                lp.user_id is not null as listener_present, lp.visibility_mode, lp.visibility_choice_completed,
                mp.name as musician_name, lp.name as listener_name, op.name as organizer_name,
                pp.name as producer_name, sp.name as studio_name, v.name as venue_name,
                mp.profile_picture_media_id as musician_avatar, lp.profile_picture_media_id as listener_avatar,
                op.profile_picture_media_id as organizer_avatar, pp.profile_picture_media_id as producer_avatar,
                sp.profile_picture_media_id as studio_avatar, vp.profile_picture_media_id as venue_avatar,
                m.id as message_id, m.content, m.message_type, m.sender_id, m.recipient_id,
                m.created_at as message_at, m.read_at
            from page p
            left join tbl_user u on u.id=case when p.user_a_id=:actor then p.user_b_id else p.user_a_id end
            left join tbl_musician_profile mp on mp.user_id=u.id
            left join "tbl_listener-profile" lp on lp.user_id=u.id
            left join tbl_organizer_profile op on op.user_id=u.id
            left join tbl_producer_profile pp on pp.user_id=u.id
            left join tbl_studio_profile sp on sp.user_id=u.id
            left join lateral (select id,name from tbl_venues where owner_id=u.id order by created_at,id limit 1) v on true
            left join tbl_venue_profile vp on vp.venue_id=v.id
            left join lateral (select id,content,message_type,sender_id,recipient_id,created_at,read_at
                from tbl_dm_message where conversation_id=p.id and deleted_at is null
                order by created_at desc,id desc limit 1) m on true
            order by p.last_message_at desc nulls last,p.id desc
            """;
}
