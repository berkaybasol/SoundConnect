package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.response.CollabActorSummary;
import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.collab.repository.CollabActorRepository;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.ownership.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CollabActorService {
    public static final Set<ProfileType> ALLOWED_TYPES = Set.of(
            ProfileType.MUSICIAN, ProfileType.BAND, ProfileType.VENUE, ProfileType.STUDIO);

    private final CollabActorRepository actorRepository;
    private final ProfileOwnershipResolver ownershipResolver;
    private final UserRepository userRepository;
    private final BandRepository bandRepository;

    @Transactional
    public List<CollabActorSummary> listMine(UUID userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
        List<OwnedProfileTarget> ownedTargets = ownershipResolver.resolveOwnedProfiles(userId, ALLOWED_TYPES);
        ownedTargets.stream().filter(target -> target.type() == ProfileType.BAND)
                .map(OwnedProfileTarget::sourceId)
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(id -> bandRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND)));
        return ownedTargets.stream()
                .map(target -> synchronize(target, userId))
                .sorted(Comparator.comparing((CollabActorSummary a) -> a.profileType().name())
                        .thenComparing(CollabActorSummary::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(CollabActorSummary::actorId))
                .toList();
    }

    @Transactional
    public CollabActor requireOwned(UUID userId, UUID actorId) {
        CollabActor actor = actorRepository.findById(actorId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_ACTOR_NOT_FOUND));
        if (!ALLOWED_TYPES.contains(actor.getProfileType())) {
            throw new SoundConnectException(ErrorType.COLLAB_INVALID_ACTOR);
        }
        OwnedProfileTarget owned = ownershipResolver.requireOwnership(
                userId, actor.getProfileType(), actor.getSourceProfileId());
        actor.setDisplayName(normalizeName(owned.displayName()));
        actor.setAvatarUrl(blankToNull(owned.profilePictureUrl()));
        actor.setActive(true);
        return actor;
    }

    private CollabActorSummary synchronize(OwnedProfileTarget target, UUID contactUserId) {
        CollabActor actor = actorRepository.findByProfileTypeAndSourceProfileId(target.type(), target.sourceId())
                .orElseGet(() -> CollabActor.builder()
                        .profileType(target.type())
                        .sourceProfileId(target.sourceId())
                        .displayName(normalizeName(target.displayName()))
                        .avatarUrl(blankToNull(target.profilePictureUrl()))
                        .active(true)
                        .build());
        actor.setDisplayName(normalizeName(target.displayName()));
        actor.setAvatarUrl(blankToNull(target.profilePictureUrl()));
        actor.setActive(true);
        actor = actorRepository.save(actor);
        return toSummary(actor, contactUserId);
    }

    public CollabActorSummary toSummary(CollabActor actor, UUID contactUserId) {
        BigDecimal rating = actor.getReviewCount() == 0
                ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(actor.getRatingSum())
                .divide(BigDecimal.valueOf(actor.getReviewCount()), 2, RoundingMode.HALF_UP);
        return new CollabActorSummary(actor.getId(), actor.getProfileType(), actor.getSourceProfileId(),
                contactUserId, actor.getDisplayName(), actor.getAvatarUrl(), rating,
                actor.getReviewCount(), actor.getCompletedJobCount());
    }

    private String normalizeName(String value) {
        return value == null || value.isBlank() ? "SoundConnect profili" : value.strip();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
