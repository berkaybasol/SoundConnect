package com.berkayb.soundconnect.modules.overthinking.profileshare;

import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor @Transactional(timeout = 10)
public class OverthinkingProfileShareService {
    private static final Set<String> PERSONAL = Set.of("ROLE_LISTENER", "ROLE_MUSICIAN", "ROLE_VENUE", "ROLE_STUDIO", "ROLE_ORGANIZER", "ROLE_PRODUCER");
    private final OverthinkingProfileShareRepository repository;
    private final UserRepository users;
    private final ListenerVisibilityPolicy visibility;
    private final OverthinkingPostRepository sources;
    private final OverthinkingPostService posts;

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public OverthinkingProfileShareResponse.State get(UUID owner, UUID postId) {
        validateId(postId); var actor = actor(owner, false);
        if (!sources.existsById(postId)) throw sourceNotFound();
        return state(postId, repository.findByOwnerUserIdAndSourcePostId(owner, postId).orElse(null), actor);
    }

    public OverthinkingProfileShareResponse.State publish(UUID owner, UUID postId, OverthinkingProfileShareUpdate command) {
        validateId(postId); String note = note(command); var actor = actor(owner, true);
        if (!actor.canPublish()) throw forbidden();
        // Account -> listener profile -> source -> publication. The account lock
        // fences absent rows; source/profile locks exclude deletion and ghost transitions.
        if (repository.lockSource(postId).isEmpty()) throw sourceNotFound();
        var existing = repository.findByOwnerUserIdAndSourcePostId(owner, postId);
        if (existing.isPresent()) {
            if (!Objects.equals(existing.get().getNote(), note))
                throw new SoundConnectException(ErrorType.OVERTHINKING_PROFILE_SHARE_ALREADY_EXISTS);
            return state(postId, existing.get(), actor);
        }
        var saved = repository.saveAndFlush(new OverthinkingProfileShare(owner, actor.profile().getProfileId(), postId, note,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
        return state(postId, saved, actor);
    }

    public void delete(UUID owner, UUID shareId) {
        validateId(shareId); actor(owner, true);
        UUID postId = repository.ownedSourceId(shareId, owner).orElseThrow(this::shareNotFound);
        if (repository.lockSource(postId).isEmpty()) throw shareNotFound();
        // Resolve after the source fence: a delete/republication cannot be
        // targeted using a previous publication ID, and source text is untouched.
        repository.delete(repository.findByIdAndOwnerUserId(shareId, owner).orElseThrow(this::shareNotFound));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<OverthinkingProfileShareResponse.Post> list(UUID viewer, UUID profileId, int page, int size) {
        validateId(profileId);
        if (page < 0 || page > 1000 || size < 1 || size > 50) throw invalid();
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt", "id"));
        active(viewer, false);
        UUID owner = repository.listenerOwner(profileId).orElseThrow(this::profileNotFound);
        Actor target;
        try { target = actor(owner, false); }
        catch (SoundConnectException hidden) { throw profileNotFound(); }
        if (!target.profile().getProfileId().equals(profileId) || !target.profile().getChoiceCompleted()) throw profileNotFound();
        if (!target.canPublish()) return PageResponse.from(Page.empty(pageable));
        var shares = repository.findByListenerProfileIdAndOwnerUserId(profileId, owner, pageable);
        var originals = posts.getByIdsForViewer(viewer, shares.stream().map(OverthinkingProfileShare::getSourcePostId).toList());
        return PageResponse.from(shares.map(share -> new OverthinkingProfileShareResponse.Post(
                share.getId(), share.getNote(), share.getPublishedAt(), originals.get(share.getSourcePostId()))));
    }

    private Actor actor(UUID owner, boolean write) {
        active(owner, write);
        var roles = users.findRoleNamesByUserId(owner);
        var personal = roles.stream().filter(PERSONAL::contains).collect(Collectors.toSet());
        if (roles.contains("ROLE_ADMIN") || roles.contains("ROLE_OWNER") || !personal.equals(Set.of("ROLE_LISTENER"))
                || !users.findExistingPersonalProfileRoleNames(owner).equals(Set.of("ROLE_LISTENER"))) throw forbidden();
        var profile = repository.lockVisibility(owner).orElseThrow(this::forbidden);
        // Apply the shared cross-module policy, with fresh scalar visibility as
        // an additional fence against a stale profile already attached by OSIV.
        boolean restricted = visibility.lockForReadAndIsPubliclyRestricted(owner);
        return new Actor(profile, !restricted && profile.getChoiceCompleted() && "STANDARD".equals(profile.getMode()));
    }
    private void active(UUID owner, boolean write) {
        if (owner == null || (write ? repository.lockActor(owner) : repository.lockActiveReader(owner)).isEmpty())
            throw new SoundConnectException(ErrorType.UNAUTHORIZED);
    }
    private OverthinkingProfileShareResponse.State state(UUID postId, OverthinkingProfileShare share, Actor actor) {
        return new OverthinkingProfileShareResponse.State(postId, share == null ? null : share.getId(), share != null,
                share == null ? null : share.getNote(), share == null ? null : share.getPublishedAt(), actor.canPublish());
    }
    private String note(OverthinkingProfileShareUpdate command) {
        if (command == null) throw invalid();
        String value = command.note() == null ? null : command.note().strip();
        if (value != null && value.isEmpty()) return null;
        if (value != null && (value.codePointCount(0, value.length()) > 500 || value.codePoints().anyMatch(c ->
                (Character.isISOControl(c) && c != '\n' && c != '\t') || (c >= 0xd800 && c <= 0xdfff)))) throw invalid();
        return value;
    }
    private void validateId(UUID id) { if (id == null || id.equals(new UUID(0, 0))) throw invalid(); }
    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.OVERTHINKING_PROFILE_SHARE_INVALID); }
    private SoundConnectException forbidden() { return new SoundConnectException(ErrorType.FORBIDDEN_ACCESS); }
    private SoundConnectException sourceNotFound() { return new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND); }
    private SoundConnectException shareNotFound() { return new SoundConnectException(ErrorType.OVERTHINKING_PROFILE_SHARE_NOT_FOUND); }
    private SoundConnectException profileNotFound() { return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND); }
    private record Actor(OverthinkingProfileShareRepository.Visibility profile, boolean canPublish) { }
}
