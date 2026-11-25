package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import com.berkayb.soundconnect.modules.profile.shared.visibility.ProfileBlockVisibility;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MusicianFeedRestrictionGuard {
    private final MusicianFeedRestrictionRepository restrictions;
    private final MusicianFeedModerationScopeResolver scopes;
    private final ProfileBlockVisibility profileBlocks;

    public MusicianFeedRestrictionGuard(MusicianFeedRestrictionRepository restrictions, MusicianFeedModerationScopeResolver scopes,
                                       ProfileBlockVisibility profileBlocks) {
        this.restrictions = restrictions;
        this.scopes = scopes;
        this.profileBlocks = profileBlocks;
    }

    public List<MusicianFeedCandidate> filter(UUID viewer, Collection<MusicianFeedCandidate> candidates) {
        if (candidates.size() > 10_000) throw new IllegalArgumentException("Too many feed candidates");
        Map<MusicianFeedCandidate, Set<String>> byCandidate = new LinkedHashMap<>();
        candidates.forEach(candidate -> byCandidate.put(candidate, scopes.presentationScopes(candidate.toResponse())));
        Set<String> blocked = restrictions.activeScopes(byCandidate.values().stream().flatMap(Collection::stream).toList());
        Set<String> hiddenItems = hiddenItems(viewer, candidates.stream().map(MusicianFeedCandidate::toResponse).toList());
        return byCandidate.entrySet().stream().filter(entry -> Collections.disjoint(entry.getValue(), blocked))
                .filter(entry -> !hiddenItems.contains(entry.getKey().itemId()))
                .map(Map.Entry::getKey).toList();
    }

    public void requireUnrestricted(UUID viewer, Collection<MusicianFeedItemResponse> items) {
        if (items.size() > 100) throw invalid();
        try {
            List<String> requested = items.stream().flatMap(item -> scopes.presentationScopes(item).stream()).toList();
            if (!restrictions.activeScopes(requested).isEmpty() || !hiddenItems(viewer, items).isEmpty()) throw invalid();
        } catch (IllegalArgumentException unresolved) { throw invalid(); }
    }

    private Set<String> hiddenItems(UUID viewer, Collection<MusicianFeedItemResponse> items) {
        Map<UUID, Set<String>> byOwner = new HashMap<>();
        Map<String, Map<UUID, Set<String>>> byTarget = new HashMap<>();
        for (var item : items) {
            // Published opportunities remain discoverable. Their application action is viewer-specific.
            if (item.type() == MusicianFeedItemType.COLLAB) continue;
            addAuthor(byOwner, item.id(), item.author());
            if (item.reason() != null) item.reason().actors().forEach(actor -> addAuthor(byOwner, item.id(), actor));
            // A friend's like, comment or follow can reference another account's publication.
            if (item.type().name().startsWith("ACTIVITY_") && item.target() != null) {
                String type = item.target().type();
                if ("PROFILE".equals(type)) type = scopes.targetProfileType(item);
                byTarget.computeIfAbsent(type, key -> new HashMap<>())
                        .computeIfAbsent(item.target().id(), key -> new HashSet<>()).add(item.id());
            }
        }
        Set<String> hidden = new HashSet<>();
        for (UUID owner : profileBlocks.hiddenOwners(viewer, byOwner.keySet())) hidden.addAll(byOwner.get(owner));
        byTarget.forEach((type, targets) -> {
            for (UUID id : profileBlocks.hiddenTargets(viewer, type, targets.keySet())) hidden.addAll(targets.get(id));
        });
        return hidden;
    }

    private void addAuthor(Map<UUID, Set<String>> items, String item, MusicianFeedItemResponse.Author author) {
        if (author != null && author.userId() != null && !"BAND".equals(author.profileType()))
            items.computeIfAbsent(author.userId(), key -> new HashSet<>()).add(item);
    }

    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.MUSICIAN_FEED_CURSOR_INVALID); }
}
