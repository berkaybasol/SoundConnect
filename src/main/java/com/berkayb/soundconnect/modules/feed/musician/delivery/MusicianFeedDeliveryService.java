package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MusicianFeedDeliveryService {
    private static final int MAX_EVIDENCE_BYTES = 32_768;
    private static final int MAX_REPLAY_BYTES = 4_194_304;
    static final String SNAPSHOT_SQL = """
            select item_id,item_type,feed_lane,target_type,target_id,absolute_position,campaign_id
            from tbl_musician_feed_delivery
            where viewer_user_id=:viewerId and feed_session_id=:sessionId and expires_at>:now
            order by absolute_position
            limit :snapshotLimit
            """;
    private static final String INSERT = """
            insert into tbl_musician_feed_delivery(
                id,viewer_user_id,feed_session_id,item_id,item_type,feed_lane,target_type,target_id,
                author_profile_type,author_profile_id,reason_code,feedback_capabilities,
                schema_version,algorithm_version,absolute_position,campaign_id,evidence_json,
                delivered_at,expires_at,purge_after)
            values(:id,:viewerId,:sessionId,:itemId,:itemType,:feedLane,:targetType,:targetId,
                :authorProfileType,:authorProfileId,:reasonCode,:capabilities,
                :schemaVersion,:algorithmVersion,:position,:campaignId,cast(:evidenceJson as jsonb),
                :deliveredAt,:expiresAt,:purgeAfter)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final MusicianFeedDeliveryTokenCodec tokens;
    private final MusicianFeedProperties properties;
    private final ObjectMapper objectMapper;

    public MusicianFeedDeliveryService(NamedParameterJdbcTemplate jdbc,
                                       MusicianFeedDeliveryTokenCodec tokens,
                                       MusicianFeedProperties properties,
                                       ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public MusicianFeedDeliverySnapshot snapshot(UUID viewerId, UUID sessionId, Instant now) {
        List<SnapshotDelivery> session = jdbc.query(SNAPSHOT_SQL,
                new MapSqlParameterSource().addValue("viewerId", viewerId)
                .addValue("sessionId", sessionId).addValue("now", Timestamp.from(now))
                .addValue("snapshotLimit", maxSessionDeliveries() + 1), this::mapSnapshot);
        if (session.size() > maxSessionDeliveries()) throw invalid();
        validateSnapshotOrder(session);
        Set<String> itemIds = session.stream().map(SnapshotDelivery::itemId)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> targets = session.stream().map(value -> MusicianFeedDeliverySnapshot.targetKey(
                        value.targetType(), value.targetId())).collect(Collectors.toUnmodifiableSet());
        Set<String> organicTargets = session.stream()
                .filter(value -> value.itemType() != MusicianFeedItemType.ACTIVITY_COMMENT)
                .map(value -> MusicianFeedDeliverySnapshot.targetKey(value.targetType(), value.targetId()))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> promotedTargets = session.stream().filter(value -> value.campaignId() != null)
                .map(value -> MusicianFeedDeliverySnapshot.targetKey(value.targetType(), value.targetId()))
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> sessionCampaigns = session.stream().map(SnapshotDelivery::campaignId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        sessionCampaigns.addAll(jdbc.queryForList("""
                select campaign_id from tbl_musician_feed_delivery
                where viewer_user_id=:viewerId and campaign_id is not null
                  and delivered_at>=:since
                group by campaign_id having count(*)>=:cap
                limit :campaignLimit
                """, new MapSqlParameterSource().addValue("viewerId", viewerId)
                .addValue("since", Timestamp.from(now.minusSeconds(86_400)))
                .addValue("cap", Math.max(1, properties.getSponsorCampaignDailyCap()))
                .addValue("campaignLimit", maxSessionDeliveries()), UUID.class));
        long next = session.stream().mapToLong(SnapshotDelivery::absolutePosition)
                .max().orElse(-1L) + 1L;
        long promotionCount = session.stream().filter(value -> value.campaignId() != null).count();
        long organicCount = 0;
        long organicAtLastPromotion = 0;
        for (SnapshotDelivery delivered : session) {
            if (delivered.campaignId() == null) organicCount++;
            else organicAtLastPromotion = organicCount;
        }
        boolean lastPromoted = !session.isEmpty() && session.getLast().campaignId() != null;
        return new MusicianFeedDeliverySnapshot(itemIds, targets, organicTargets, promotedTargets,
                sessionCampaigns, next, promotionCount, organicAtLastPromotion, lastPromoted,
                session.isEmpty() ? null : session.getLast().itemType(),
                session.isEmpty() ? null : session.getLast().lane());
    }

    private SnapshotDelivery mapSnapshot(ResultSet row, int index) throws SQLException {
        try {
            String itemId = row.getString("item_id");
            String itemTypeValue = row.getString("item_type");
            String laneValue = row.getString("feed_lane");
            String targetType = row.getString("target_type");
            UUID targetId = row.getObject("target_id", UUID.class);
            long position = row.getLong("absolute_position");
            if (row.wasNull() || itemId == null || itemId.isBlank()
                    || itemTypeValue == null || itemTypeValue.isBlank()
                    || laneValue == null || laneValue.isBlank()
                    || targetType == null || targetType.isBlank() || targetId == null || position < 0) {
                throw corruptSnapshot();
            }
            MusicianFeedItemType itemType = MusicianFeedItemType.valueOf(itemTypeValue);
            MusicianFeedLane lane = MusicianFeedLane.valueOf(laneValue);
            return new SnapshotDelivery(itemId, itemType, lane, targetType, targetId,
                    position, row.getObject("campaign_id", UUID.class));
        } catch (IllegalArgumentException invalidEnum) {
            throw corruptSnapshot(invalidEnum);
        }
    }

    private void validateSnapshotOrder(List<SnapshotDelivery> session) {
        long previousPosition = -1;
        Set<String> itemIds = new HashSet<>();
        for (SnapshotDelivery delivered : session) {
            if (delivered.absolutePosition() <= previousPosition || !itemIds.add(delivered.itemId())) {
                throw corruptSnapshot();
            }
            previousPosition = delivered.absolutePosition();
        }
    }

    private IllegalStateException corruptSnapshot() {
        return new IllegalStateException("Corrupt musician-feed delivery snapshot row");
    }

    private IllegalStateException corruptSnapshot(Throwable cause) {
        return new IllegalStateException("Corrupt musician-feed delivery snapshot row", cause);
    }

    @Transactional(readOnly = true)
    public MusicianFeedPageResponse requireReplay(
            UUID viewerId,
            UUID sessionId,
            long requestPosition,
            String requestFingerprint,
            int requestedLimit,
            Set<MusicianFeedItemType> supportedTypes,
            Instant now
    ) {
        return findReplay(viewerId, sessionId, requestPosition, requestFingerprint,
                requestedLimit, canonicalTypes(supportedTypes), now).orElseThrow(this::invalid);
    }

    @Transactional(readOnly = true)
    public Optional<MusicianFeedPageResponse> replay(
            UUID viewerId,
            UUID sessionId,
            long requestPosition,
            String requestFingerprint,
            int requestedLimit,
            Set<MusicianFeedItemType> supportedTypes,
            Instant now
    ) {
        return findReplay(viewerId, sessionId, requestPosition, requestFingerprint,
                requestedLimit, canonicalTypes(supportedTypes), now);
    }

    @Transactional
    public List<MusicianFeedItemResponse> recordPage(
            UUID viewerId,
            UUID sessionId,
            Instant anchor,
            int schemaVersion,
            String algorithmVersion,
            long startPosition,
            List<MusicianFeedItemResponse> items,
            Instant now
    ) {
        List<MusicianFeedLane> lanes = items == null ? List.of() : items.stream()
                .map(value -> legacyLane(value.type())).toList();
        return recordPage(viewerId, sessionId, anchor, schemaVersion, algorithmVersion,
                startPosition, items, lanes, now);
    }

    @Transactional
    public List<MusicianFeedItemResponse> recordPage(
            UUID viewerId,
            UUID sessionId,
            Instant anchor,
            int schemaVersion,
            String algorithmVersion,
            long startPosition,
            List<MusicianFeedItemResponse> items,
            List<MusicianFeedLane> lanes,
            Instant now
    ) {
        if (items == null) throw invalid();
        if (lanes == null || lanes.size() != items.size() || lanes.stream().anyMatch(Objects::isNull)) {
            throw invalid();
        }
        if (startPosition < 0 || startPosition + items.size() > maxSessionDeliveries()) throw invalid();
        lockSession(viewerId, sessionId);
        Long persistedNext = jdbc.queryForObject("""
                select coalesce(max(absolute_position),-1)+1
                from tbl_musician_feed_delivery
                where viewer_user_id=:viewerId and feed_session_id=:sessionId and expires_at>:now
                """, new MapSqlParameterSource().addValue("viewerId", viewerId)
                .addValue("sessionId", sessionId).addValue("now", Timestamp.from(now)), Long.class);
        if (persistedNext == null || persistedNext != startPosition) throw invalid();
        // Empty terminal pages still reserve/validate the continuation
        // position. Otherwise a stale request could journal an empty replay
        // after another node had already advanced the delivery ledger.
        if (items.isEmpty()) return List.of();
        Instant expiresAt = anchor.plus(properties.getDeliveryTtl());
        Instant purgeAfter = now.plus(properties.getDeliveryRetention());
        Instant telemetryFence = expiresAt.plus(properties.getTelemetryRetention());
        if (purgeAfter.isBefore(telemetryFence)) purgeAfter = telemetryFence;
        List<MapSqlParameterSource> batch = new ArrayList<>(items.size());
        List<UUID> ids = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            MusicianFeedItemResponse item = items.get(index);
            long position = startPosition + index;
            UUID id = UUID.nameUUIDFromBytes((viewerId + ":" + sessionId + ":" + item.id())
                    .getBytes(StandardCharsets.UTF_8));
            ids.add(id);
            String capabilities = item.feedbackCapabilities().stream().map(Enum::name).sorted()
                    .collect(Collectors.joining(","));
            batch.add(new MapSqlParameterSource().addValue("id", id).addValue("viewerId", viewerId)
                    .addValue("sessionId", sessionId).addValue("itemId", item.id())
                    .addValue("itemType", item.type().name())
                    .addValue("feedLane", lanes.get(index).name())
                    .addValue("targetType", item.target().type()).addValue("targetId", item.target().id())
                    .addValue("authorProfileType", item.author() == null ? null : item.author().profileType())
                    .addValue("authorProfileId", item.author() == null ? null : item.author().profileId())
                    .addValue("reasonCode", item.reason() == null ? null : item.reason().code().name())
                    .addValue("capabilities", capabilities).addValue("schemaVersion", schemaVersion)
                    .addValue("algorithmVersion", algorithmVersion).addValue("position", position)
                    .addValue("campaignId", item.promotion() == null ? null : item.promotion().campaignId())
                    .addValue("evidenceJson", evidence(item)).addValue("deliveredAt", Timestamp.from(now))
                    .addValue("expiresAt", Timestamp.from(expiresAt)).addValue("purgeAfter", Timestamp.from(purgeAfter)));
        }
        jdbc.batchUpdate(INSERT, batch.toArray(MapSqlParameterSource[]::new));
        List<MusicianFeedDeliveredItem> rows = jdbc.query("""
                select * from tbl_musician_feed_delivery
                where viewer_user_id=:viewerId and feed_session_id=:sessionId and id in (:ids)
                """, new MapSqlParameterSource().addValue("viewerId", viewerId)
                .addValue("sessionId", sessionId).addValue("ids", ids), this::map);
        Map<String, MusicianFeedDeliveredItem> byItem = rows.stream().collect(Collectors.toMap(
                MusicianFeedDeliveredItem::itemId, value -> value));
        if (byItem.size() != items.size()) throw new IllegalStateException("Incomplete musician-feed delivery batch");
        List<MusicianFeedItemResponse> delivered = new ArrayList<>(items.size());
        for (MusicianFeedItemResponse item : items) {
            MusicianFeedDeliveredItem row = byItem.get(item.id());
            delivered.add(item.withDelivery(row.absolutePosition(), tokens.encode(row)));
        }
        return List.copyOf(delivered);
    }

    /**
     * Commits delivery identities and the exact continuation response in one
     * transaction. The session advisory lock makes concurrent retries either
     * create the page once or return the already committed response.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MusicianFeedPageResponse recordPageAndReplay(
            UUID viewerId,
            UUID sessionId,
            Instant anchor,
            int schemaVersion,
            String algorithmVersion,
            long startPosition,
            String requestFingerprint,
            int requestedLimit,
            Set<MusicianFeedItemType> supportedTypes,
            List<MusicianFeedItemResponse> items,
            List<MusicianFeedLane> lanes,
            String nextCursor,
            boolean hasMore,
            Instant generatedAt
    ) {
        if (requestFingerprint == null || requestFingerprint.length() != 43
                || requestedLimit < 1 || requestedLimit > properties.getMaxPageSize()
                || supportedTypes == null || supportedTypes.isEmpty()
                || items == null || items.size() > requestedLimit
                || items.stream().anyMatch(item -> item == null || !supportedTypes.contains(item.type()))
                || (hasMore && (nextCursor == null || nextCursor.isBlank()))
                || (!hasMore && nextCursor != null)) throw invalid();
        String canonicalTypes = canonicalTypes(supportedTypes);
        lockSession(viewerId, sessionId);
        Optional<MusicianFeedPageResponse> replay = findReplay(viewerId, sessionId, startPosition,
                requestFingerprint, requestedLimit, canonicalTypes, generatedAt);
        if (replay.isPresent()) return replay.get();

        List<MusicianFeedItemResponse> delivered = recordPage(viewerId, sessionId, anchor,
                schemaVersion, algorithmVersion, startPosition, items, lanes, generatedAt);
        MusicianFeedPageResponse response = new MusicianFeedPageResponse(schemaVersion, algorithmVersion,
                sessionId, generatedAt, delivered, nextCursor, hasMore);
        Instant cursorFence = anchor.plus(properties.getCursorTtl());
        Instant deliveryFence = anchor.plus(properties.getDeliveryTtl());
        Instant expiresAt = cursorFence.isBefore(deliveryFence) ? cursorFence : deliveryFence;
        if (!expiresAt.isAfter(generatedAt)) throw invalid();
        String responseJson = serializeReplay(response);
        UUID replayId = UUID.nameUUIDFromBytes((viewerId + ":" + sessionId + ":" + startPosition)
                .getBytes(StandardCharsets.UTF_8));
        int inserted = jdbc.update("""
                insert into tbl_musician_feed_page_replay(
                    id,viewer_user_id,feed_session_id,request_position,request_fingerprint,
                    requested_limit,supported_types,schema_version,algorithm_version,response_json,
                    created_at,expires_at)
                values(:id,:viewerId,:sessionId,:position,:fingerprint,:requestedLimit,:supportedTypes,
                    :schemaVersion,:algorithmVersion,:responseJson,:createdAt,:expiresAt)
                on conflict(viewer_user_id,feed_session_id,request_position) do nothing
                """, new MapSqlParameterSource().addValue("id", replayId)
                .addValue("viewerId", viewerId).addValue("sessionId", sessionId)
                .addValue("position", startPosition).addValue("fingerprint", requestFingerprint)
                .addValue("requestedLimit", requestedLimit).addValue("supportedTypes", canonicalTypes)
                .addValue("schemaVersion", schemaVersion).addValue("algorithmVersion", algorithmVersion)
                .addValue("responseJson", responseJson).addValue("createdAt", Timestamp.from(generatedAt))
                .addValue("expiresAt", Timestamp.from(expiresAt)));
        if (inserted != 1) throw invalid();
        return response;
    }

    @Transactional(readOnly = true)
    public MusicianFeedDeliveredItem require(String token, UUID viewerId, String expectedItemId, Instant now) {
        MusicianFeedDeliveryTokenCodec.Claims claims = tokens.decode(token, viewerId, now);
        List<MusicianFeedDeliveredItem> rows = jdbc.query("""
                select * from tbl_musician_feed_delivery
                where id=:id and viewer_user_id=:viewerId and expires_at>:now
                """, new MapSqlParameterSource().addValue("id", claims.deliveryId())
                .addValue("viewerId", viewerId).addValue("now", Timestamp.from(now)), this::map);
        if (rows.size() != 1) throw invalid();
        MusicianFeedDeliveredItem row = rows.getFirst();
        validateClaims(claims, row, expectedItemId);
        return row;
    }

    private MusicianFeedDeliveredItem map(ResultSet row, int index) throws SQLException {
        String capabilities = row.getString("feedback_capabilities");
        EnumSet<MusicianFeedFeedbackAction> parsed = EnumSet.noneOf(MusicianFeedFeedbackAction.class);
        if (capabilities != null && !capabilities.isBlank()) {
            for (String value : capabilities.split(",")) parsed.add(MusicianFeedFeedbackAction.valueOf(value));
        }
        return new MusicianFeedDeliveredItem(row.getObject("id", UUID.class),
                row.getObject("viewer_user_id", UUID.class), row.getObject("feed_session_id", UUID.class),
                row.getString("item_id"), MusicianFeedItemType.valueOf(row.getString("item_type")),
                row.getString("target_type"), row.getObject("target_id", UUID.class),
                row.getString("author_profile_type"), row.getObject("author_profile_id", UUID.class),
                row.getString("reason_code"), Set.copyOf(parsed), row.getInt("schema_version"),
                row.getString("algorithm_version"), row.getLong("absolute_position"),
                row.getObject("campaign_id", UUID.class), row.getString("evidence_json"),
                row.getTimestamp("delivered_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
                row.getTimestamp("purge_after").toInstant(),
                MusicianFeedLane.valueOf(row.getString("feed_lane")));
    }

    private Optional<MusicianFeedPageResponse> findReplay(
            UUID viewerId,
            UUID sessionId,
            long requestPosition,
            String requestFingerprint,
            int requestedLimit,
            String supportedTypes,
            Instant now
    ) {
        List<PageReplayRow> rows = jdbc.query("""
                select request_fingerprint,requested_limit,supported_types,schema_version,
                       algorithm_version,response_json,expires_at
                from tbl_musician_feed_page_replay
                where viewer_user_id=:viewerId and feed_session_id=:sessionId
                  and request_position=:position
                """, new MapSqlParameterSource().addValue("viewerId", viewerId)
                .addValue("sessionId", sessionId).addValue("position", requestPosition),
                (row, index) -> new PageReplayRow(row.getString("request_fingerprint"),
                        row.getInt("requested_limit"), row.getString("supported_types"),
                        row.getInt("schema_version"), row.getString("algorithm_version"),
                        row.getString("response_json"), row.getTimestamp("expires_at").toInstant()));
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() != 1) throw invalid();
        PageReplayRow row = rows.getFirst();
        if (!row.expiresAt().isAfter(now)
                || !Objects.equals(row.requestFingerprint(), requestFingerprint)
                || row.requestedLimit() != requestedLimit
                || !Objects.equals(row.supportedTypes(), supportedTypes)) throw invalid();
        try {
            MusicianFeedPageResponse response = objectMapper.readValue(
                    row.responseJson(), MusicianFeedPageResponse.class);
            validateReplayResponse(response, viewerId, sessionId, requestPosition, requestedLimit, row, now);
            return Optional.of(response);
        } catch (SoundConnectException known) {
            throw known;
        } catch (Exception corrupt) {
            throw invalid();
        }
    }

    private void validateReplayResponse(
            MusicianFeedPageResponse response,
            UUID viewerId,
            UUID sessionId,
            long requestPosition,
            int requestedLimit,
            PageReplayRow row,
            Instant now
    ) {
        if (response == null || response.schemaVersion() != row.schemaVersion()
                || !Objects.equals(response.algorithmVersion(), row.algorithmVersion())
                || !sessionId.equals(response.feedSessionId())
                || response.items().size() > requestedLimit
                || (response.hasMore() && (response.nextCursor() == null || response.nextCursor().isBlank()))) {
            throw invalid();
        }
        List<MusicianFeedDeliveryTokenCodec.Claims> claims = new ArrayList<>(response.items().size());
        for (int index = 0; index < response.items().size(); index++) {
            MusicianFeedItemResponse item = response.items().get(index);
            if (item.position() != requestPosition + index
                    || item.impressionToken() == null || item.impressionToken().isBlank()) throw invalid();
            MusicianFeedDeliveryTokenCodec.Claims decoded = tokens.decode(
                    item.impressionToken(), viewerId, now);
            if (!sessionId.equals(decoded.feedSessionId())
                    || decoded.absolutePosition() != item.position()) throw invalid();
            claims.add(decoded);
        }
        // The replay fence never outlives delivery tokens. Validate every
        // signed identity against the ledger with one bounded read so a
        // partially damaged page can never return unusable action tokens.
        if (!claims.isEmpty()) {
            Set<UUID> deliveryIds = claims.stream().map(MusicianFeedDeliveryTokenCodec.Claims::deliveryId)
                    .collect(Collectors.toSet());
            if (deliveryIds.size() != claims.size()) throw invalid();
            List<MusicianFeedDeliveredItem> deliveries = jdbc.query("""
                    select * from tbl_musician_feed_delivery
                    where viewer_user_id=:viewerId and feed_session_id=:sessionId
                      and id in (:ids) and expires_at>:now
                    """, new MapSqlParameterSource().addValue("viewerId", viewerId)
                    .addValue("sessionId", sessionId).addValue("ids", deliveryIds)
                    .addValue("now", Timestamp.from(now)), this::map);
            if (deliveries.size() != claims.size()) throw invalid();
            Map<UUID, MusicianFeedDeliveredItem> byId = deliveries.stream().collect(Collectors.toMap(
                    MusicianFeedDeliveredItem::deliveryId, value -> value));
            for (int index = 0; index < claims.size(); index++) {
                MusicianFeedDeliveryTokenCodec.Claims decoded = claims.get(index);
                MusicianFeedDeliveredItem delivery = byId.get(decoded.deliveryId());
                if (delivery == null) throw invalid();
                validateClaims(decoded, delivery, response.items().get(index).id());
            }
        }
    }

    private void validateClaims(MusicianFeedDeliveryTokenCodec.Claims claims,
                                MusicianFeedDeliveredItem row,
                                String expectedItemId) {
        if ((expectedItemId != null && !expectedItemId.equals(row.itemId()))
                || !claims.feedSessionId().equals(row.feedSessionId())
                || !claims.itemId().equals(row.itemId())
                || !claims.targetType().equals(row.targetType())
                || !claims.targetId().equals(row.targetId())
                || claims.schemaVersion() != row.schemaVersion()
                || !claims.algorithmVersion().equals(row.algorithmVersion())
                || claims.absolutePosition() != row.absolutePosition()) throw invalid();
    }

    private String serializeReplay(MusicianFeedPageResponse response) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(response);
            if (json.length > MAX_REPLAY_BYTES) throw new IllegalStateException("Feed replay page exceeds bound");
            return new String(json, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not serialize musician-feed replay page", failure);
        }
    }

    private String canonicalTypes(Set<MusicianFeedItemType> supportedTypes) {
        return supportedTypes.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private void lockSession(UUID viewerId, UUID sessionId) {
        var lockParameters = new MapSqlParameterSource().addValue("lockKey", viewerId + ":" + sessionId);
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text),0))",
                lockParameters, ignored -> { });
    }

    private MusicianFeedLane legacyLane(MusicianFeedItemType type) {
        if (type == MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE
                || type == MusicianFeedItemType.TABLEGROUP_PROFILE_SHARE) {
            return MusicianFeedLane.MODULE_SHARE;
        }
        if (type == MusicianFeedItemType.PROFILE_COMPLETION) return MusicianFeedLane.SYSTEM;
        return MusicianFeedLane.FOLLOWING;
    }

    private String evidence(MusicianFeedItemResponse item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("itemId", item.id());
        value.put("itemType", item.type());
        value.put("author", item.author());
        value.put("target", item.target());
        value.put("reason", item.reason());
        value.put("payload", item.payload());
        try {
            byte[] json = objectMapper.writeValueAsBytes(value);
            if (json.length <= MAX_EVIDENCE_BYTES) return new String(json, StandardCharsets.UTF_8);
            value.put("payload", Map.of("omitted", true, "reason", "MAX_EVIDENCE_BYTES"));
            json = objectMapper.writeValueAsBytes(value);
            if (json.length > MAX_EVIDENCE_BYTES) throw new IllegalStateException("Feed evidence metadata too large");
            return new String(json, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize public feed delivery evidence", exception);
        }
    }

    private int maxSessionDeliveries() {
        return Math.max(properties.getMaxPageSize(), Math.min(properties.getMaxSessionDeliveries(), 10_000));
    }

    private SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.BAD_REQUEST);
    }

    private record PageReplayRow(String requestFingerprint, int requestedLimit, String supportedTypes,
                                 int schemaVersion, String algorithmVersion, String responseJson,
                                 Instant expiresAt) { }
    private record SnapshotDelivery(String itemId, MusicianFeedItemType itemType,
                                    MusicianFeedLane lane, String targetType, UUID targetId,
                                    long absolutePosition, UUID campaignId) { }
}
