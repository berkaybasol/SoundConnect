package com.berkayb.soundconnect.modules.comment.support;

import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.comment.repository.CommentAuthorRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.MANDATORY)
public class CommentAuthorBatchResolver {
    private final CommentAuthorRepository repository;
    private final MediaAssetService media;

    public Map<UUID, UserSummaryDto> resolve(Collection<UUID> authorIds) {
        if (authorIds.isEmpty()) return Map.of();
        if (authorIds.size() > 50) throw new IllegalArgumentException("Comment author page exceeds 50");
        // Restriction resolution fails closed and retains its visibility locks through response mapping.
        var ghosts = new HashMap<UUID, CommentAuthorRepository.ListenerIdentity>();
        for (var listener : repository.lockListenerIdentities(authorIds)) {
            if (!listener.getChoiceCompleted() || !"STANDARD".equals(listener.getMode())) ghosts.put(listener.getUserId(), listener);
        }
        Map<UUID, UserSummaryDto> result = new HashMap<>();
        var visibleIds = authorIds.stream().filter(id -> !ghosts.containsKey(id)).toList();
        var rows = visibleIds.isEmpty() ? List.<CommentAuthorRepository.Candidate>of() : repository.candidates(visibleIds);
        Set<UUID> mediaIds = new LinkedHashSet<>();
        for (var row : rows) for (UUID id : mediaIds(row)) if (id != null) mediaIds.add(id);
        ghosts.values().stream().filter(CommentAuthorRepository.ListenerIdentity::getChoiceCompleted)
                .map(CommentAuthorRepository.ListenerIdentity::getAvatar).filter(Objects::nonNull).forEach(mediaIds::add);
        Map<UUID, String> urls = Map.of();
        if (!mediaIds.isEmpty()) {
            try { urls = Optional.ofNullable(media.getDisplayUrlMap(List.copyOf(mediaIds))).orElse(Map.of()); }
            catch (RuntimeException exception) { log.warn("Comment avatars unavailable; no raw private-media fallback", exception); }
        }
        for (var ghost : ghosts.values()) {
            boolean pending = !ghost.getChoiceCompleted();
            String name = ghost.getUsername();
            result.put(ghost.getUserId(), new UserSummaryDto(ghost.getUserId(),
                    pending || name == null || name.isBlank() ? "Kullanici" : name.trim(),
                    pending || ghost.getAvatar() == null ? null : urls.get(ghost.getAvatar()), ListenerVisibilityMode.GHOST));
        }
        for (var row : rows) {
            String avatar = null;
            for (UUID id : mediaIds(row)) {
                String candidate = id == null ? null : urls.get(id);
                if (candidate != null && !candidate.isBlank()) { avatar = candidate; break; }
            }
            // Legacy URLs only when no authoritative profile avatar is configured.
            if (avatar == null && Arrays.stream(mediaIds(row)).allMatch(Objects::isNull)) {
                String raw = row.getLegacyAvatar();
                if (raw != null && (raw.startsWith("https://") || raw.startsWith("http://"))) avatar = raw;
            }
            result.put(row.getUserId(), new UserSummaryDto(row.getUserId(), row.getUsername(), avatar));
        }
        return Map.copyOf(result);
    }

    private UUID[] mediaIds(CommentAuthorRepository.Candidate row) {
        UUID[] profiles = new UUID[]{row.getMusician(), row.getListener(), row.getOrganizer(), row.getProducer(),
                row.getStudio(), row.getVenue()};
        return Arrays.stream(profiles).anyMatch(Objects::nonNull) ? profiles : new UUID[]{legacyId(row.getLegacyAvatar())};
    }

    private UUID legacyId(String value) {
        try { return value == null ? null : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
