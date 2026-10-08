package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/** Reads effective ranking state and candidate-scoped preferences, never a viewer's full history. */
@Repository
public class MusicianFeedFeedbackReader {
    static final int PROBE_BATCH_SIZE = 500;
    static final int MAX_CANDIDATES = 10_000;
    private static final int SHOW_LESS_SATURATION = 10;
    // The lookup index ends in id to provide this order without sorting. With
    // LIMIT alone PostgreSQL can prefer repeated scans of a viewer's history.
    static final String RANKING_SQL = """
            select types.item_type, totals.capped_count
            from (values %s) types(item_type)
            cross join lateral (
                select count(*)::integer as capped_count from (
                    select 1 from tbl_musician_feed_feedback feedback
                    where feedback.viewer_user_id=:viewerId and feedback.action='SHOW_LESS'
                        and feedback.item_type=types.item_type
                    order by feedback.id
                    limit %d
                ) capped
            ) totals
            """.formatted(Arrays.stream(MusicianFeedItemType.values())
            .map(type -> "('" + type.name() + "')").collect(Collectors.joining(",")), SHOW_LESS_SATURATION);

    private final NamedParameterJdbcTemplate jdbc;

    public MusicianFeedFeedbackReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MusicianFeedFeedbackSnapshot ranking(UUID viewer) {
        Objects.requireNonNull(viewer, "Viewer is required");
        var counts = new EnumMap<MusicianFeedItemType, Integer>(MusicianFeedItemType.class);
        jdbc.query(RANKING_SQL, new MapSqlParameterSource("viewerId", viewer), row -> {
            int count = row.getInt("capped_count");
            if (count > 0) counts.put(MusicianFeedItemType.valueOf(row.getString("item_type")), count);
        });
        return new MusicianFeedFeedbackSnapshot(Set.of(), Set.of(), counts, rankingVersion(counts));
    }

    public MusicianFeedFeedbackSnapshot forCandidates(
            UUID viewer, MusicianFeedFeedbackSnapshot ranking,
            Collection<MusicianFeedCandidate> organic, Collection<MusicianFeedCandidate> promotions) {
        Objects.requireNonNull(viewer, "Viewer is required");
        Objects.requireNonNull(ranking, "Ranking state is required");
        Objects.requireNonNull(organic, "Organic candidates are required");
        Objects.requireNonNull(promotions, "Promotion candidates are required");
        // The core also validates each provider's result limit. Keep accidental
        // direct callers from turning this bounded lookup into a history loader.
        if ((long) organic.size() + promotions.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("Too many musician-feed candidates for feedback lookup");
        }
        Set<Probe> probes = new LinkedHashSet<>();
        addProbes(probes, organic);
        addProbes(probes, promotions);
        if (probes.isEmpty()) return ranking;

        Set<String> hidden = new HashSet<>(ranking.hiddenItemIds());
        Set<String> muted = new HashSet<>(ranking.mutedAuthorKeys());
        List<Probe> values = List.copyOf(probes);
        for (int start = 0; start < values.size(); start += PROBE_BATCH_SIZE) {
            List<Probe> batch = values.subList(start, Math.min(start + PROBE_BATCH_SIZE, values.size()));
            MapSqlParameterSource parameters = new MapSqlParameterSource("viewerId", viewer);
            StringJoiner requested = new StringJoiner(",");
            for (int index = 0; index < batch.size(); index++) {
                Probe probe = batch.get(index);
                requested.add("(:action" + index + ",:scope" + index + ")");
                parameters.addValue("action" + index, probe.action()).addValue("scope" + index, probe.scope());
            }
            // LIMIT 1 keeps each preference check a lookup of the existing
            // unique (viewer, action, scope_key) key, including very old rows.
            String sql = """
                    select requested.action,requested.scope_key
                    from (values %s) requested(action,scope_key)
                    join lateral (
                        select 1 from tbl_musician_feed_feedback feedback
                        where feedback.viewer_user_id=:viewerId and feedback.action=requested.action
                            and feedback.scope_key=requested.scope_key
                        limit 1
                    ) matched on true
                    """.formatted(requested);
            jdbc.query(sql, parameters, row -> {
                String scope = row.getString("scope_key");
                if ("MUTE_AUTHOR".equals(row.getString("action"))) muted.add(scope.substring("AUTHOR:".length()));
                else hidden.add(scope.substring("ITEM:".length()));
            });
        }
        return new MusicianFeedFeedbackSnapshot(hidden, muted, ranking.showLessCounts(),
                ranking.rankingContextVersion());
    }

    private void addProbes(Set<Probe> probes, Collection<MusicianFeedCandidate> candidates) {
        for (MusicianFeedCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "Candidate is required");
            probes.add(new Probe("HIDE", "ITEM:" + candidate.itemId()));
            probes.add(new Probe("REPORT", "ITEM:" + candidate.itemId()));
            var author = candidate.author();
            if (author != null && author.profileType() != null && author.profileId() != null) {
                probes.add(new Probe("MUTE_AUTHOR", "AUTHOR:" + MusicianFeedFeedbackSnapshot.authorKey(
                        author.profileType(), author.profileId())));
            }
        }
    }

    private String rankingVersion(Map<MusicianFeedItemType, Integer> counts) {
        // Only effective weights affect cursor compatibility. A repeated action
        // or an eleventh SHOW_LESS has no additional effect on the mixer score.
        String canonical = Arrays.stream(MusicianFeedItemType.values())
                .map(type -> type.name() + "=" + counts.getOrDefault(type, 0))
                .collect(Collectors.joining("|", "musician-feedback-ranking-v2|", ""));
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Probe(String action, String scope) { }
}
