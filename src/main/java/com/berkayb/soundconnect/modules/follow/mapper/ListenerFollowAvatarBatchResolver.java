package com.berkayb.soundconnect.modules.follow.mapper;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** The presence of a null value explicitly clears the obsolete User avatar. */
@Component
@RequiredArgsConstructor
public class ListenerFollowAvatarBatchResolver {
    private final ListenerProfileRepository profiles;
    private final MediaAssetService media;

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, String> resolve(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        var ids = userIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        Map<UUID, String> result = new LinkedHashMap<>();
        for (int offset = 0; offset < ids.size(); offset += 50) {
            var batch = ids.subList(offset, Math.min(offset + 50, ids.size()));
            // The controller resolves ghost identity first and retains those
            // shared locks through both projection and response construction.
            var references = profiles.findAvatarReferences(batch);
            var mediaIds = references.stream().map(ListenerProfileRepository.AvatarReference::getMediaId)
                    .filter(Objects::nonNull).distinct().toList();
            var urls = mediaIds.isEmpty() ? Map.<UUID, String>of() : media.getDisplayUrlMap(mediaIds);
            for (var reference : references) {
                result.put(reference.getUserId(), reference.getMediaId() == null ? null : urls.get(reference.getMediaId()));
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
