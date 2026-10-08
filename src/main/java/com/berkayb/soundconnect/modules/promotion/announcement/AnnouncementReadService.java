package com.berkayb.soundconnect.modules.promotion.announcement;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.promotion.entity.Promotion;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.repository.PromotionRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnnouncementReadService {
    private final AnnouncementAccess access;
    private final PromotionRepository promotions;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true, timeout = 5, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public AnnouncementPage directory(UUID viewerId, String cursor, int limit) {
        ProfileType profile = access.viewerProfile(viewerId);
        return batch(viewerId, profile, null, cursor, limit, "DIRECTORY", 50);
    }

    @Transactional
    public AnnouncementResponse get(UUID viewerId, UUID id) {
        access.requireVisible(viewerId, id);
        return project(viewerId, List.of(id)).getFirst();
    }

    @Transactional
    public void requireVisible(UUID viewerId, UUID id) { access.requireVisible(viewerId, id); }

    @Transactional(readOnly = true, timeout = 5, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<AnnouncementResponse> findForFeed(UUID viewerId, String profileType, Instant anchor, int limit) {
        return findForFeedBatch(viewerId, profileType, anchor, null, limit).items();
    }

    @Transactional(readOnly = true, timeout = 5, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public AnnouncementPage findForFeedBatch(UUID viewerId, String profileType, Instant anchor, String cursor, int limit) {
        ProfileType profile = requireProfile(viewerId, profileType);
        if (anchor == null) throw invalid();
        return batch(viewerId, profile, anchor, cursor, limit, "FEED", 160);
    }

    @Transactional(readOnly = true, timeout = 5, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<AnnouncementResponse> findForFeedByIds(UUID viewerId, String profileType, Collection<UUID> ids, Instant now) {
        ProfileType profile = requireProfile(viewerId, profileType);
        if (ids == null || ids.size() > 160 || ids.stream().anyMatch(Objects::isNull) || now == null) throw invalid();
        if (ids.isEmpty()) return List.of();
        var parameters = parameters(profile, now).addValue("ids", ids);
        List<UUID> visible = jdbc.queryForList("select p.id from tlb_promotion p where p.id in (:ids) and "
                + AnnouncementAccess.VISIBLE_SQL + " order by p.first_published_at desc,p.id desc", parameters, UUID.class);
        return project(viewerId, visible);
    }

    private ProfileType requireProfile(UUID viewerId, String requested) {
        ProfileType actual = access.viewerProfile(viewerId);
        if (!actual.name().equals(requested)) throw new SoundConnectException(ErrorType.ANNOUNCEMENT_FORBIDDEN);
        return actual;
    }

    private AnnouncementPage batch(UUID viewerId, ProfileType profile, Instant fixedAnchor,
                                   String encoded, int limit, String source, int maximum) {
        if (limit < 1 || limit > maximum) throw invalid();
        String scope = source + ":" + viewerId + ":" + profile.name();
        Cursor cursor = Cursor.decode(encoded, scope, fixedAnchor);
        Instant anchor = cursor != null ? cursor.anchor() : fixedAnchor != null ? fixedAnchor : Instant.now();
        var parameters = parameters(profile, Instant.now()).addValue("anchor", Timestamp.from(anchor))
                .addValue("limit", limit + 1);
        String after = "";
        if (cursor != null) {
            parameters.addValue("afterTime", Timestamp.from(cursor.time())).addValue("afterId", cursor.id());
            after = " and (p.first_published_at,p.id)<(:afterTime,:afterId)";
        }
        List<UUID> ids = jdbc.queryForList("select p.id from tlb_promotion p where "
                + AnnouncementAccess.VISIBLE_SQL + " and p.first_published_at<=:anchor"
                + after + " order by p.first_published_at desc,p.id desc limit :limit", parameters, UUID.class);
        boolean hasMore = ids.size() > limit;
        List<AnnouncementResponse> items = project(viewerId, ids.subList(0, Math.min(ids.size(), limit)));
        String next = null;
        if (hasMore && !items.isEmpty()) {
            AnnouncementResponse last = items.getLast();
            next = new Cursor(scope, anchor, last.firstPublishedAt(), last.id()).encode();
        }
        return new AnnouncementPage(items, next, hasMore);
    }

    /** Bounded batch projection shared with admin reads; no access URLs are generated or stored. */
    @Transactional(readOnly = true, timeout = 5, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    List<AnnouncementResponse> project(UUID viewerId, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        if (ids.size() > 160) throw invalid();
        Map<UUID, Promotion> values = promotions.findAnnouncementsWithMediaAndAudience(ids).stream()
                .collect(Collectors.toMap(Promotion::getId, Function.identity()));
        var parameters = new MapSqlParameterSource("ids", ids).addValue("viewer", viewerId);
        Map<UUID, long[]> engagement = new HashMap<>();
        jdbc.query("""
                select target_id,count(*) as total,coalesce(bool_or(user_id=cast(:viewer as uuid)),false) as liked
                from tbl_like where target_type='ANNOUNCEMENT' and target_id in (:ids) group by target_id
                """, parameters, row -> {
            engagement.put(row.getObject("target_id", UUID.class), new long[]{row.getLong("total"), 0, row.getBoolean("liked") ? 1 : 0});
        });
        jdbc.query("""
                select target_id,count(*) as total from tbl_comment
                where target_type='ANNOUNCEMENT' and target_id in (:ids) and is_deleted=false group by target_id
                """, parameters, row -> {
            engagement.computeIfAbsent(row.getObject("target_id", UUID.class), ignored -> new long[3])[1] = row.getLong("total");
        });
        Set<String> hidden = viewerId == null ? Set.of() : new HashSet<>(jdbc.queryForList("""
                select item_id from tbl_musician_feed_feedback where viewer_user_id=:viewer and action='HIDE'
                    and item_id in (:itemIds)
                """, new MapSqlParameterSource("viewer", viewerId).addValue("itemIds",
                ids.stream().map(id -> "ANNOUNCEMENT:" + id).toList()), String.class));
        Instant now = Instant.now();
        return ids.stream().map(values::get).filter(Objects::nonNull).map(value -> {
            long[] counts = engagement.getOrDefault(value.getId(), new long[3]);
            var media = value.getMediaAsset();
            return new AnnouncementResponse(value.getId(), value.getVersion(), value.getTitle(), value.getDescription(),
                    value.getTargetProfiles(), status(value, now), instant(value.getStartDate()), instant(value.getEndDate()),
                    value.getFirstPublishedAt(), instant(value.getCreatedAt()), instant(value.getUpdatedAt()),
                    media == null ? null : new AnnouncementResponse.Media(media.getId(), media.getKind(), media.getStatus(),
                            media.getStreamingProtocol(), media.getWidth(), media.getHeight(), media.getDurationSeconds()),
                    new AnnouncementResponse.Engagement(counts[0], counts[1], counts[2] == 1),
                    hidden.contains("ANNOUNCEMENT:" + value.getId()));
        }).toList();
    }

    public static AnnouncementStatus status(Promotion value, Instant now) {
        if (value.getStatus() == PromotionStatus.ARCHIVED) return AnnouncementStatus.ARCHIVED;
        if (value.getStatus() == PromotionStatus.DRAFT) return AnnouncementStatus.DRAFT;
        if (value.getStatus() != PromotionStatus.ACTIVE
                || value.getEndDate() != null && !instant(value.getEndDate()).isAfter(now)) return AnnouncementStatus.ENDED;
        return value.getStartDate() != null && instant(value.getStartDate()).isAfter(now)
                ? AnnouncementStatus.SCHEDULED : AnnouncementStatus.PUBLISHED;
    }

    public static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }

    private static MapSqlParameterSource parameters(ProfileType profile, Instant now) {
        return new MapSqlParameterSource("profile", profile.name()).addValue("now", AnnouncementAccess.utcTimestamp(now));
    }

    private static SoundConnectException invalid() { return new SoundConnectException(ErrorType.ANNOUNCEMENT_INVALID); }

    record Cursor(String scope, Instant anchor, Instant time, UUID id) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (scope + "|" + anchor + "|" + time + "|" + id).getBytes(StandardCharsets.UTF_8));
        }
        static Cursor decode(String encoded, String scope, Instant anchor) {
            if (encoded == null || encoded.isBlank()) return null;
            try {
                if (encoded.length() > 512) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 4 || !parts[0].equals(scope)) throw new IllegalArgumentException();
                Cursor result = new Cursor(parts[0], Instant.parse(parts[1]), Instant.parse(parts[2]), UUID.fromString(parts[3]));
                if (anchor != null && !anchor.equals(result.anchor()) || result.time().isAfter(result.anchor())
                        || result.time().isBefore(Instant.EPOCH) || result.anchor().isAfter(Instant.now().plusSeconds(30))) {
                    throw new IllegalArgumentException();
                }
                return result;
            } catch (RuntimeException malformed) {
                throw new SoundConnectException(ErrorType.ANNOUNCEMENT_CURSOR_INVALID);
            }
        }
    }
}
