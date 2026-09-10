package com.berkayb.soundconnect.modules.tablegroup.profileshare;

import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.repository.CommentTargetAccessRepository;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(timeout = 10)
public class TableGroupProfileShareService {
    private static final EngagementTargetType TARGET = EngagementTargetType.TABLE_GROUP_POST;

    private final TableGroupProfileShareRepository repository;
    private final CommentTargetAccessRepository access;
    private final LikeRepository likes;
    private final CommentRepository comments;

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public TableGroupProfileShareResponse.State get(UUID owner, UUID tableId) {
        validateId(tableId);
        var actor = actor(owner, false);
        if (repository.lockSource(tableId).isEmpty()) throw sourceNotFound();
        repository.freezeEndedSource(tableId);
        boolean eligible = repository.eligibleSource(tableId, owner, Instant.now());
        var share = repository.findByOwnerUserIdAndTableGroupId(owner, tableId).orElse(null);
        return state(tableId, share, actor, eligible);
    }

    public TableGroupProfileShareResponse.State publish(UUID owner, UUID tableId, TableGroupProfileShareUpdate command) {
        validateId(tableId);
        String note = note(command);
        var actor = actor(owner, true);
        if (!actor.canPublish()) throw forbidden();
        // Account -> listener visibility -> aggregate -> publication. Every
        // membership/lifecycle mutation takes the conflicting aggregate lock.
        if (repository.lockSource(tableId).isEmpty()) throw sourceNotFound();
        if (!repository.eligibleSource(tableId, owner, Instant.now())) {
            throw new SoundConnectException(ErrorType.TABLE_GROUP_PROFILE_SHARE_FORBIDDEN);
        }
        var existing = repository.findByOwnerUserIdAndTableGroupId(owner, tableId);
        if (existing.isPresent()) {
            if (!Objects.equals(existing.get().getNote(), note)) {
                throw new SoundConnectException(ErrorType.TABLE_GROUP_PROFILE_SHARE_ALREADY_EXISTS);
            }
            return state(tableId, existing.get(), actor, true);
        }
        var saved = repository.saveAndFlush(new TableGroupProfileShare(owner, actor.visibility().getProfileId(), tableId,
                note, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        return state(tableId, saved, actor, true);
    }

    public void delete(UUID owner, UUID shareId) {
        validateId(shareId);
        actor(owner, true);
        UUID tableId = repository.ownedSourceId(shareId, owner).orElseThrow(this::shareNotFound);
        if (repository.lockSource(tableId).isEmpty()) throw shareNotFound();
        // Removal remains available after leaving, closure or switching to ghost.
        // Exact IDs fence a delayed DELETE from targeting a later republication.
        var share = repository.findByIdAndOwnerUserId(shareId, owner).orElseThrow(this::shareNotFound);
        repository.delete(share);
        repository.flush();
        // The publication's delete trigger removes its likes/comments atomically,
        // including source/profile/account cascades outside this service.
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public PageResponse<TableGroupProfileShareResponse.Post> list(UUID viewer, UUID profileId, int page, int size) {
        validateId(profileId);
        if (page < 0 || page > 1000 || size < 1 || size > 50) throw invalid();
        var pageable = PageRequest.of(page, size);
        Actor target = publicTarget(viewer, profileId);
        if (!target.canPublish()) return PageResponse.from(Page.empty(pageable));
        UUID owner = target.owner();
        // One request cutoff keeps a card from falling between live and archived
        // branches when the wall clock crosses expiry during this transaction.
        Instant cutoff = Instant.now();
        freezeEndedSources(repository.endedSources(profileId, owner, cutoff));
        var shares = repository.publicShares(profileId, owner, cutoff, pageable);
        if (shares.isEmpty()) return PageResponse.from(new PageImpl<>(java.util.List.of(), pageable, shares.getTotalElements()));
        var posts = posts(viewer, shares.getContent());
        return PageResponse.from(new PageImpl<>(posts, pageable, shares.getTotalElements()));
    }

    /** Bounded refresh of visible publication IDs; omitted IDs are no longer readable. */
    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public List<TableGroupProfileShareResponse.Post> lookup(UUID viewer, UUID profileId, List<UUID> shareIds) {
        validateId(profileId);
        if (shareIds == null || shareIds.isEmpty() || shareIds.size() > 50
                || shareIds.stream().distinct().count() != shareIds.size()) throw invalid();
        shareIds.forEach(this::validateId);
        Actor target = publicTarget(viewer, profileId);
        if (!target.canPublish()) return List.of();
        UUID owner = target.owner();
        Instant cutoff = Instant.now();
        freezeEndedSources(repository.endedSourcesByShareIds(profileId, owner, cutoff, shareIds));
        return posts(viewer, repository.publicSharesByIds(profileId, owner, cutoff, shareIds));
    }

    private Actor publicTarget(UUID viewer, UUID profileId) {
        active(viewer, false);
        UUID owner = repository.listenerOwner(profileId).orElseThrow(this::profileNotFound);
        Actor target;
        try { target = actor(owner, false); }
        catch (SoundConnectException hidden) { throw profileNotFound(); }
        if (!target.visibility().getProfileId().equals(profileId) || !target.visibility().getChoiceCompleted()) {
            throw profileNotFound();
        }
        return target;
    }

    private void freezeEndedSources(List<UUID> tableIds) {
        // Deterministic aggregate order matches multi-table lifecycle writes.
        // The function rechecks the deadline after acquiring the aggregate lock.
        for (UUID tableId : tableIds) {
            repository.freezeEndedSource(tableId);
        }
    }

    private List<TableGroupProfileShareResponse.Post> posts(UUID viewer, List<TableGroupProfileShare> shares) {
        if (shares.isEmpty()) return List.of();
        var liveIds = shares.stream().filter(share -> share.getFinalSource() == null)
                .map(TableGroupProfileShare::getTableGroupId).distinct().toList();
        var sources = liveIds.isEmpty() ? Map.<UUID, TableGroupProfileShareResponse.Source>of() : sources(liveIds);
        var shareIds = shares.stream().map(TableGroupProfileShare::getId).toList();
        var likeCounts = likes.countByTargetTypeAndTargetIdIn(TARGET, shareIds).stream()
                .collect(Collectors.toMap(LikeRepository.TargetCountProjection::getTargetId, LikeRepository.TargetCountProjection::getCount));
        var commentCounts = comments.countByTargetTypeAndTargetIdIn(TARGET, shareIds).stream()
                .collect(Collectors.toMap(CommentRepository.TargetCountProjection::getTargetId, CommentRepository.TargetCountProjection::getCount));
        var liked = likes.findLikedTargetIds(viewer, TARGET, shareIds);
        return shares.stream().map(share -> new TableGroupProfileShareResponse.Post(
                share.getId(), share.getNote(), share.getPublishedAt(),
                share.getFinalSource() != null ? share.getFinalSource() : sources.get(share.getTableGroupId()),
                likeCounts.getOrDefault(share.getId(), 0L), commentCounts.getOrDefault(share.getId(), 0L),
                liked.contains(share.getId()))).toList();
    }

    private Actor actor(UUID owner, boolean write) {
        active(owner, write);
        // Reuse the listener-only role/profile boundary already applied to event
        // publications and their engagement; absent and corrupt profiles fail closed.
        if (!access.eligibleListenerPostAuthor(owner)) throw forbidden();
        var visibility = repository.lockVisibility(owner).orElseThrow(this::forbidden);
        return new Actor(owner, visibility);
    }

    private void active(UUID owner, boolean write) {
        if (owner == null || (write ? repository.lockActor(owner) : repository.lockActiveReader(owner)).isEmpty()) {
            throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        }
    }

    private TableGroupProfileShareResponse.State state(UUID tableId, TableGroupProfileShare share, Actor actor, boolean eligible) {
        var source = share != null && share.isFinalSourceFrozen() ? share.getFinalSource()
                : eligible ? sources(java.util.List.of(tableId)).get(tableId) : null;
        return new TableGroupProfileShareResponse.State(tableId, share == null ? null : share.getId(), share != null,
                share == null ? null : share.getNote(), share == null ? null : share.getPublishedAt(),
                actor.canPublish() && eligible, source);
    }

    private Map<UUID, TableGroupProfileShareResponse.Source> sources(Collection<UUID> ids) {
        return repository.sourceCards(ids).stream().map(source -> new TableGroupProfileShareResponse.Source(
                source.getId(), source.getDescription(), source.getVenueName(), source.getCityName(), source.getDistrictName(),
                source.getMeetingAt(), source.getExpiresAt(), TableGroupStatus.valueOf(source.getStatus()),
                source.getMaxPersonCount(), source.getAcceptedCount()))
                .collect(Collectors.toMap(TableGroupProfileShareResponse.Source::id, Function.identity()));
    }

    private String note(TableGroupProfileShareUpdate command) {
        if (command == null) throw invalid();
        String value = command.note() == null ? null : command.note().strip();
        if (value != null && value.isEmpty()) return null;
        if (value != null && (value.codePointCount(0, value.length()) > 500 || value.codePoints().anyMatch(c ->
                (Character.isISOControl(c) && c != '\n' && c != '\t') || (c >= 0xd800 && c <= 0xdfff)))) throw invalid();
        return value;
    }

    private void validateId(UUID id) { if (id == null || id.equals(new UUID(0, 0))) throw invalid(); }
    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.TABLE_GROUP_PROFILE_SHARE_INVALID); }
    private SoundConnectException forbidden() { return new SoundConnectException(ErrorType.FORBIDDEN_ACCESS); }
    private SoundConnectException sourceNotFound() { return new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND); }
    private SoundConnectException shareNotFound() { return new SoundConnectException(ErrorType.TABLE_GROUP_PROFILE_SHARE_NOT_FOUND); }
    private SoundConnectException profileNotFound() { return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND); }

    private record Actor(UUID owner, TableGroupProfileShareRepository.Visibility visibility) {
        boolean canPublish() { return visibility.getChoiceCompleted() && "STANDARD".equals(visibility.getMode()); }
    }
}
