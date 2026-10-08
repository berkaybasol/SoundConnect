package com.berkayb.soundconnect.modules.feed.listener.core;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPageResponse;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Listener publication boundary, shared by first delivery and immutable page replay.
 * Source visibility remains the responsibility of the existing provider/replay guards.
 * The media audience is re-read in bounded batches so editing an audience invalidates
 * an old page even when its serialized payload still says MAINSTAGE.
 */
@Component
public class ListenerFeedContentPolicy {
    private static final int BATCH_SIZE = 200;
    private static final Set<String> PROFILE_TYPES = Set.of("MUSICIAN", "LISTENER", "VENUE", "BAND");
    private static final Set<String> FORBIDDEN_KINDS = Set.of("STUDIO", "STUDIO_PROFILE", "COLLAB",
            "COLLAB_LISTING", "PROFILE_COMPLETION", "SPONSORED");
    private static final Set<String> TYPE_FIELDS = Set.of("type", "profileType", "ownerType", "targetType", "targetItemType");
    private static final Set<MusicianFeedItemType> ALLOWED_TYPES = EnumSet.complementOf(
            EnumSet.of(MusicianFeedItemType.COLLAB, MusicianFeedItemType.PROFILE_COMPLETION,
                    MusicianFeedItemType.SPONSORED));
    private static final String MEDIA_SQL = """
            select id from tbl_media_asset
            where id in (:ids) and content_audience='MAINSTAGE'
              and owner_type in ('MUSICIAN_PROFILE','LISTENER_PROFILE','VENUE_PROFILE','BAND')
              and status='READY' and visibility='PUBLIC'
            """;
    private static final String OVERTHINKING_SHARE_SQL = """
            select share.id from tbl_overthinking_profile_share share
            join tbl_overthinking_post source on source.id=share.source_post_id
            where share.id in (:ids) and
            """ + ListenerFeedSourceSql.overthinking("source");
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ListenerFeedContentPolicy(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, timeout = 5)
    public List<MusicianFeedCandidate> filterCandidates(UUID viewerId, Collection<MusicianFeedCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Map<MusicianFeedCandidate, Probe> probes = new LinkedHashMap<>();
        for (var candidate : candidates) {
            if (candidate == null) continue;
            Probe probe = inspect(candidate.toResponse());
            if (probe.allowed()) probes.put(candidate, probe);
        }
        Set<UUID> allowedMedia = allowedMedia(probes.values());
        Set<UUID> allowedShares = allowedShares(probes.values());
        return probes.entrySet().stream().filter(entry -> allowedMedia.containsAll(entry.getValue().mediaIds())
                        && allowedShares.containsAll(entry.getValue().shareIds()))
                .map(Map.Entry::getKey).toList();
    }

    @Transactional(readOnly = true, timeout = 5)
    public void requireReplayEligible(UUID viewerId, MusicianFeedPageResponse page, Instant now) {
        if (page == null) throw invalidCursor();
        List<Probe> probes = page.items().stream().map(this::inspect).toList();
        if (probes.stream().anyMatch(probe -> !probe.allowed())) throw invalidCursor();
        Set<UUID> allowedMedia = allowedMedia(probes);
        Set<UUID> allowedShares = allowedShares(probes);
        if (probes.stream().anyMatch(probe -> !allowedMedia.containsAll(probe.mediaIds())
                || !allowedShares.containsAll(probe.shareIds()))) throw invalidCursor();
    }

    private Probe inspect(MusicianFeedItemResponse item) {
        if (item == null || !ALLOWED_TYPES.contains(item.type()) || item.promotion() != null
                || !allowedAuthor(item.author()) || item.target() == null || item.target().id() == null
                || FORBIDDEN_KINDS.contains(item.target().type())) return Probe.denied();
        if (item.reason() != null && item.reason().actors().stream().anyMatch(actor -> !allowedAuthor(actor))) {
            return Probe.denied();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        if ("MEDIA".equals(item.target().type())) {
            if (item.target().id() == null) return Probe.denied();
            ids.add(item.target().id());
        }
        try {
            JsonNode payload = mapper.valueToTree(item.payload());
            if (!inspectTree(payload, ids, 0)) return Probe.denied();
            if (item.type() == MusicianFeedItemType.ANNOUNCEMENT) {
                JsonNode audiences = payload.path("targetProfiles");
                boolean listener = false;
                if (audiences.isArray()) for (JsonNode audience : audiences) {
                    if ("LISTENER".equals(audience.asText())) listener = true;
                }
                if (!listener) return Probe.denied();
            }
            Set<UUID> shareIds = "OVERTHINKING_PROFILE_SHARE".equals(item.target().type())
                    ? Set.of(item.target().id()) : Set.of();
            return new Probe(true, Set.copyOf(ids), shareIds);
        } catch (IllegalArgumentException exception) {
            return Probe.denied();
        }
    }

    private boolean inspectTree(JsonNode value, Set<UUID> mediaIds, int depth) {
        if (depth > 16) return false;
        if (value == null || value.isNull()) return depth > 0;
        if (value.isArray()) {
            for (JsonNode child : value) if (!inspectTree(child, mediaIds, depth + 1)) return false;
        } else if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey();
                JsonNode child = entry.getValue();
                if (TYPE_FIELDS.contains(key) && child.isTextual()
                        && FORBIDDEN_KINDS.contains(child.textValue().toUpperCase(Locale.ROOT))) return false;
                if ("contentAudience".equals(key) && !"MAINSTAGE".equals(child.asText())) return false;
                if ("profileType".equals(key) && child.isTextual() && !PROFILE_TYPES.contains(child.textValue())) return false;
                if ("mediaAssetId".equals(key) && !child.isNull()) mediaIds.add(UUID.fromString(child.asText()));
                if (!inspectTree(child, mediaIds, depth + 1)) return false;
            }
        }
        return true;
    }

    private Set<UUID> allowedMedia(Collection<Probe> probes) {
        Set<UUID> unique = new LinkedHashSet<>();
        probes.forEach(probe -> unique.addAll(probe.mediaIds()));
        return queryAllowed(MEDIA_SQL, unique);
    }

    private Set<UUID> allowedShares(Collection<Probe> probes) {
        Set<UUID> unique = new LinkedHashSet<>();
        probes.forEach(probe -> unique.addAll(probe.shareIds()));
        return queryAllowed(OVERTHINKING_SHARE_SQL, unique);
    }

    private Set<UUID> queryAllowed(String sql, Set<UUID> unique) {
        if (unique.isEmpty()) return Set.of();
        List<UUID> ordered = new ArrayList<>(unique);
        Set<UUID> result = new HashSet<>();
        for (int from = 0; from < ordered.size(); from += BATCH_SIZE) {
            var batch = ordered.subList(from, Math.min(ordered.size(), from + BATCH_SIZE));
            result.addAll(jdbc.query(sql, Map.of("ids", batch),
                    (row, index) -> row.getObject("id", UUID.class)));
        }
        return result;
    }

    private static boolean allowedAuthor(MusicianFeedItemResponse.Author author) {
        return author == null || (author.profileType() != null && PROFILE_TYPES.contains(author.profileType()));
    }

    private static SoundConnectException invalidCursor() {
        return new SoundConnectException(ErrorType.MUSICIAN_FEED_CURSOR_INVALID);
    }

    private record Probe(boolean allowed, Set<UUID> mediaIds, Set<UUID> shareIds) {
        static Probe denied() { return new Probe(false, Set.of(), Set.of()); }
    }
}
