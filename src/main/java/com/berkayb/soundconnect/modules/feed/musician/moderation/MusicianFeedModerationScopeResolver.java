package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/** Resolves reported stories separately from the content those stories happen to describe. */
@Component
public class MusicianFeedModerationScopeResolver {
    private static final Set<String> PROFILE_TYPES = Set.of("MUSICIAN", "LISTENER", "STUDIO", "VENUE", "BAND");
    private static final Set<String> ACTIVITY_TARGETS = Set.of("MEDIA", "COLLAB", "EVENT", "EVENT_POST",
            "OVERTHINKING_PROFILE_SHARE", "TABLE_GROUP_POST", "PROFILE");
    private final ObjectMapper mapper;

    public MusicianFeedModerationScopeResolver(ObjectMapper mapper) { this.mapper = mapper; }

    public String reportScope(MusicianFeedModerationSubject subject) {
        if (subject == null || subject.targetId() == null || subject.targetType() == null || subject.itemId() == null
                || subject.itemId().length() > 256 || subject.itemType() == null
                || !subject.itemId().startsWith(subject.itemType() + ":")
                || subject.itemId().length() == subject.itemType().length() + 1
                || !consistentEvidence(subject)) return null;
        MusicianFeedItemType type;
        try { type = MusicianFeedItemType.valueOf(subject.itemType()); }
        catch (IllegalArgumentException unknown) { return null; }
        return switch (type) {
            case TRACK, PROFILE_MEDIA -> target(subject, "MEDIA");
            case EVENT -> target(subject, "EVENT");
            case EVENT_PROFILE_SHARE -> target(subject, "EVENT_POST");
            case OVERTHINKING_PROFILE_SHARE -> target(subject, "OVERTHINKING_PROFILE_SHARE");
            case TABLEGROUP_PROFILE_SHARE -> target(subject, "TABLE_GROUP_POST");
            case PROFILE -> "PROFILE".equals(subject.targetType())
                    ? profileScope(subject.targetId(), child(subject.evidence(), "payload"), child(subject.evidence(), "author")) : null;
            case ACTIVITY_COMMENT -> ACTIVITY_TARGETS.contains(subject.targetType())
                    && uuid(subject.itemId().substring("ACTIVITY_COMMENT:".length())) != null ? "ITEM:" + subject.itemId() : null;
            case ACTIVITY_LIKE, ACTIVITY_FOLLOW -> ACTIVITY_TARGETS.contains(subject.targetType()) ? "ITEM:" + subject.itemId() : null;
            case SPONSORED -> "STANDALONE".equals(subject.targetType()) ? "ITEM:" + subject.itemId() : null;
            // Collab retains its native moderation aggregate. Completion does not offer REPORT.
            case COLLAB, PROFILE_COMPLETION, ANNOUNCEMENT -> null;
        };
    }

    public Set<String> presentationScopes(MusicianFeedItemResponse item) {
        if (item.type() == MusicianFeedItemType.PROFILE_COMPLETION) return Set.of();
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add("ITEM:" + item.id());
        var target = item.target();
        if (target == null || target.id() == null) return Set.copyOf(scopes);
        JsonNode payload = null;
        if ("PROFILE".equals(target.type()) || "EVENT_POST".equals(target.type())) {
            payload = mapper.valueToTree(item.payload());
            if (item.type() == MusicianFeedItemType.ACTIVITY_FOLLOW || item.type() == MusicianFeedItemType.ACTIVITY_LIKE
                    || item.type() == MusicianFeedItemType.ACTIVITY_COMMENT) payload = child(payload, "targetPayload");
        }
        if ("PROFILE".equals(target.type())) {
            String profile = profileScope(target.id(), payload,
                    item.type() == MusicianFeedItemType.PROFILE ? mapper.valueToTree(item.author()) : null);
            if (profile == null) throw new IllegalArgumentException("Feed profile restriction scope is unresolved");
            scopes.add(profile);
        } else if (ACTIVITY_TARGETS.contains(target.type()) || "STANDALONE".equals(target.type())) {
            scopes.add("TARGET:" + target.type() + ":" + target.id());
        }
        if ("EVENT_POST".equals(target.type())) {
            UUID eventId = uuid(text(child(payload, "event"), "id"));
            if (eventId == null) throw new IllegalArgumentException("Feed event publication source is unresolved");
            scopes.add("TARGET:EVENT:" + eventId);
        }
        return Set.copyOf(scopes);
    }

    public String describeScope(String scope) {
        if (scope == null) return null;
        if (scope.startsWith("ITEM:")) return "Yalnız bu akış aktivitesi veya reklam kartı";
        if (scope.startsWith("TARGET:MEDIA:")) return "Bu medyanın müzisyen akışındaki tüm gösterimleri";
        if (scope.startsWith("TARGET:EVENT:")) return "Bu etkinliğin müzisyen akışındaki tüm gösterimleri";
        if (scope.startsWith("TARGET:PROFILE:")) return "Bu profil kartı ve profili hedefleyen akış aktiviteleri";
        return "Bu paylaşımın müzisyen akışındaki tüm gösterimleri";
    }

    private String target(MusicianFeedModerationSubject subject, String expected) {
        return expected.equals(subject.targetType()) ? "TARGET:" + expected + ":" + subject.targetId() : null;
    }

    private String profileScope(UUID expected, JsonNode payload, JsonNode author) {
        String type = text(payload, "profileType");
        UUID id = uuid(text(payload, "profileId"));
        String authorType = text(author, "profileType");
        UUID authorId = uuid(text(author, "profileId"));
        if (type == null && id == null) { type = authorType; id = authorId; }
        if (!PROFILE_TYPES.contains(type == null ? "" : type) || !expected.equals(id)) return null;
        if (authorType != null && (!authorType.equals(type) || !expected.equals(authorId))) return null;
        return "TARGET:PROFILE:" + type + ":" + expected;
    }

    private boolean consistentEvidence(MusicianFeedModerationSubject subject) {
        JsonNode evidence = subject.evidence();
        if (evidence == null || evidence.isNull()) return true;
        if (!evidence.isObject()) return false;
        JsonNode target = child(evidence, "target");
        return matchesIfPresent(evidence, "itemId", subject.itemId())
                && matchesIfPresent(evidence, "itemType", subject.itemType())
                && matchesIfPresent(evidence, "id", subject.itemId())
                && matchesIfPresent(evidence, "type", subject.itemType())
                && (target == null || (subject.targetType().equals(text(target, "type"))
                    && subject.targetId().equals(uuid(text(target, "id")))));
    }

    private static boolean matchesIfPresent(JsonNode value, String key, String expected) {
        JsonNode field = child(value, key);
        return field == null || (field.isTextual() && expected.equals(field.asText()));
    }

    private static JsonNode child(JsonNode value, String key) { return value == null ? null : value.get(key); }
    private static String text(JsonNode value, String key) {
        JsonNode child = child(value, key);
        return child != null && child.isTextual() ? child.asText() : null;
    }
    private static UUID uuid(String value) {
        if (value == null) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }
}
