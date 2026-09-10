package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.modules.event.discovery.*;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class EventAudienceService {
    private static final Set<String> PERSONAL = Set.of("ROLE_LISTENER", "ROLE_MUSICIAN", "ROLE_VENUE", "ROLE_STUDIO", "ROLE_ORGANIZER", "ROLE_PRODUCER");
    private final EventAudienceRepository repository;
    private final UserRepository users;
    private final EventDiscoveryService cards;
    private final EventScheduleClock clock;
    private final LikeRepository likes;
    private final CommentRepository comments;

    /** Lightweight current-account preflight before consuming the shared mutation quota. */
    @Transactional(timeout = 5)
    public void requireAuthority(UUID userId) { actor(userId, false); }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public EventIntentResponse.State get(UUID userId, UUID eventId) {
        if (eventId == null) throw invalid();
        Actor actor = actor(userId, false);
        var stored = repository.findById(new EventAudienceIntent.Id(userId, eventId));
        var event = eventCards(List.of(eventId)).get(eventId);
        if (event == null && stored.isEmpty()) throw eventNotFound();
        return state(stored.orElseGet(() -> new EventAudienceIntent(userId, eventId)), actor, event, clock.instant());
    }

    @Transactional(timeout = 5)
    public EventIntentResponse.State update(UUID userId, UUID eventId, EventIntentUpdate command) {
        String note = validate(eventId, command);
        // Account lock is the creation fence for absent (actor,event) rows. The
        // profile shared lock serializes publication with ghost-mode transitions.
        Actor actor = actor(userId, true);
        if (!actor.listener() && (command.publishedOnProfile() || note != null)) throw forbidden();
        repository.lockEvent(eventId);
        var current = repository.findById(new EventAudienceIntent.Id(userId, eventId))
                .orElseGet(() -> new EventAudienceIntent(userId, eventId));
        EventResponseDto event = eventCards(List.of(eventId)).get(eventId);
        Instant now = clock.instant();
        boolean same = current.getIntent() == command.intent() && current.isPublishedOnProfile() == command.publishedOnProfile()
                && Objects.equals(current.getNote(), note);
        if (current.getVersion() != command.expectedVersion()) {
            if (current.getVersion() == command.expectedVersion() + 1 && same) return state(current, actor, event, now);
            throw new SoundConnectException(ErrorType.EVENT_INTENT_VERSION_CONFLICT);
        }
        if (event == null && current.getUpdatedAt() == null) throw eventNotFound();
        if (same) return state(current, actor, event, now);
        boolean removal = command.intent() == EventIntent.NONE;
        boolean unpublishOnly = command.intent() == current.getIntent() && !command.publishedOnProfile() && note == null;
        if (!removal && !unpublishOnly) {
            if (event == null) throw eventNotFound();
            if (ended(event, now)) throw new SoundConnectException(ErrorType.EVENT_INTENT_CLOSED);
            boolean retainedPublication = actor.ghost()
                    && actor.profile().getChoiceCompleted() && current.isPublishedOnProfile()
                    && Objects.equals(current.getNote(), note);
            if (command.publishedOnProfile() && !actor.mayPublish() && !retainedPublication) throw forbidden();
        }
        boolean newlyPublished = command.publishedOnProfile() && !current.isPublishedOnProfile();
        current.setIntent(command.intent()); current.setPublishedOnProfile(command.publishedOnProfile()); current.setNote(note);
        current.setVersion(current.getVersion() + 1); current.setUpdatedAt(now);
        current.setPublishedAt(command.publishedOnProfile() ? newlyPublished ? now : current.getPublishedAt() : null);
        current.setPostId(command.publishedOnProfile() ? newlyPublished ? UUID.randomUUID() : current.getPostId() : null);
        // NONE is retained as a version tombstone: a late request cannot resurrect a cleared plan.
        repository.saveAndFlush(current);
        return state(current, actor, event, now);
    }

    /** Remove this exact publication, preserving the owner's private attendance choice. */
    @Transactional(timeout = 5)
    public EventIntentResponse.State deletePost(UUID userId, UUID postId) {
        if (postId == null) throw invalid();
        Actor actor = actor(userId, true);
        if (!actor.listener()) throw forbidden();
        UUID eventId = repository.publishedEventId(userId, postId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND));
        repository.lockEvent(eventId);
        var current = repository.findById(new EventAudienceIntent.Id(userId, eventId)).orElseThrow(this::eventNotFound);
        // Account serialization fences concurrent PUT/DELETE and an old post ID cannot remove a republication.
        if (!current.isPublishedOnProfile() || !postId.equals(current.getPostId()))
            throw new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND);
        current.setPublishedOnProfile(false); current.setNote(null); current.setPublishedAt(null); current.setPostId(null);
        current.setVersion(current.getVersion() + 1); current.setUpdatedAt(clock.instant());
        repository.saveAndFlush(current);
        return state(current, actor, eventCards(List.of(eventId)).get(eventId), clock.instant());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public PageResponse<EventIntentResponse.State> mine(UUID userId, EventIntentPeriod period, int page, int size) {
        var pageable = page(period, page, size); Actor actor = actor(userId, false); Instant now = clock.instant();
        var local = LocalDateTime.ofInstant(now, EventScheduleClock.ZONE);
        var ids = repository.privateIds(userId, period.name(), local.toLocalDate(), local.toLocalTime(), LocalTime.MIDNIGHT, pageable);
        if (ids.isEmpty()) return PageResponse.from(new PageImpl<>(List.of(), pageable, ids.getTotalElements()));
        var values = states(userId, ids.getContent()); var events = eventCards(ids.getContent());
        var engagement = engagement(userId, values.values());
        return PageResponse.from(ids.map(id -> {
            var result = state(values.get(id), actor, events.get(id), now);
            var counts = result.postId() == null ? Counts.EMPTY : engagement.getOrDefault(result.postId(), Counts.EMPTY);
            return result.withEngagement(counts.likes(), counts.comments(), counts.liked());
        }));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public PageResponse<EventIntentResponse.Post> posts(UUID viewer, UUID profileId, EventIntentPeriod period, int page, int size) {
        var pageable = page(period, page, size);
        activeAccount(viewer, false);
        UUID author = repository.listenerUserId(profileId).orElseThrow(this::profileNotFound);
        Actor target;
        try { target = actor(author, false); }
        catch (SoundConnectException hidden) { throw profileNotFound(); }
        if (!target.listener() || !target.profile().getProfileId().equals(profileId) || !target.profile().getChoiceCompleted()) throw profileNotFound();
        if (!target.mayPublish()) return PageResponse.from(Page.empty(pageable));
        Instant now = clock.instant(); var local = LocalDateTime.ofInstant(now, EventScheduleClock.ZONE);
        var ids = repository.publicIds(author, period.name(), local.toLocalDate(), local.toLocalTime(), LocalTime.MIDNIGHT, pageable);
        if (ids.isEmpty()) return PageResponse.from(new PageImpl<>(List.of(), pageable, ids.getTotalElements()));
        var values = states(author, ids.getContent()); var events = eventCards(ids.getContent());
        var engagement = engagement(viewer, values.values());
        Actor viewerActor = viewer.equals(author) ? target : optionalActor(viewer);
        var viewerValues = viewerActor == null ? Map.<UUID, EventAudienceIntent>of()
                : viewer.equals(author) ? values : states(viewer, ids.getContent());
        return PageResponse.from(ids.map(id -> new EventIntentResponse.Post(id, values.get(id).getIntent(), values.get(id).getNote(),
                values.get(id).getPublishedAt(), ended(events.get(id), now), events.get(id), values.get(id).getPostId(),
                engagement.getOrDefault(values.get(id).getPostId(), Counts.EMPTY).likes(),
                engagement.getOrDefault(values.get(id).getPostId(), Counts.EMPTY).comments(),
                engagement.getOrDefault(values.get(id).getPostId(), Counts.EMPTY).liked(),
                viewerActor == null ? null : state(viewerValues.getOrDefault(id, new EventAudienceIntent(viewer, id)),
                        viewerActor, events.get(id), now))));
    }

    private Actor optionalActor(UUID viewer) {
        try { return actor(viewer, false); }
        catch (SoundConnectException failure) {
            if (failure.getErrorType() == ErrorType.FORBIDDEN_ACCESS) return null;
            throw failure;
        }
    }

    /** Engagement belongs to the publication, never the underlying shared event. */
    private Map<UUID, Counts> engagement(UUID viewer, Collection<EventAudienceIntent> values) {
        var postIds = values.stream().filter(EventAudienceIntent::isPublishedOnProfile)
                .map(EventAudienceIntent::getPostId).filter(Objects::nonNull).distinct().toList();
        if (postIds.isEmpty()) return Map.of();
        var likeCounts = likes.countByTargetTypeAndTargetIdIn(EngagementTargetType.EVENT_POST, postIds).stream()
                .collect(Collectors.toMap(LikeRepository.TargetCountProjection::getTargetId, LikeRepository.TargetCountProjection::getCount));
        var commentCounts = comments.countByTargetTypeAndTargetIdIn(EngagementTargetType.EVENT_POST, postIds).stream()
                .collect(Collectors.toMap(CommentRepository.TargetCountProjection::getTargetId, CommentRepository.TargetCountProjection::getCount));
        var liked = likes.findLikedTargetIds(viewer, EngagementTargetType.EVENT_POST, postIds);
        return postIds.stream().collect(Collectors.toMap(Function.identity(), id ->
                new Counts(likeCounts.getOrDefault(id, 0L), commentCounts.getOrDefault(id, 0L), liked.contains(id))));
    }

    private record Counts(long likes, long comments, boolean liked) {
        private static final Counts EMPTY = new Counts(0, 0, false);
    }

    private Actor actor(UUID userId, boolean write) {
        activeAccount(userId, write);
        var roles = users.findRoleNamesByUserId(userId);
        var personal = roles.stream().filter(PERSONAL::contains).collect(Collectors.toSet());
        if (roles.contains("ROLE_ADMIN") || roles.contains("ROLE_OWNER") || personal.size() != 1
                || !(personal.contains("ROLE_LISTENER") || personal.contains("ROLE_MUSICIAN"))
                || !users.findExistingPersonalProfileRoleNames(userId).equals(personal)) throw forbidden();
        boolean listener = personal.contains("ROLE_LISTENER");
        var profile = listener ? repository.listenerVisibility(userId).orElseThrow(this::forbidden) : null;
        return new Actor(listener, profile);
    }

    private void activeAccount(UUID userId, boolean write) {
        if (userId == null || (write ? repository.lockActor(userId) : repository.lockActiveAccountForRead(userId)).isEmpty())
            throw new SoundConnectException(ErrorType.UNAUTHORIZED);
    }
    private Map<UUID, EventAudienceIntent> states(UUID userId, List<UUID> ids) {
        return repository.pageStates(userId, ids).stream().collect(Collectors.toMap(value -> value.getId().getEventId(), Function.identity()));
    }
    private Map<UUID, EventResponseDto> eventCards(List<UUID> ids) {
        return cards.present(repository.eventCards(ids)).stream()
                .filter(event -> event.eventDate() != null && event.startTime() != null)
                .collect(Collectors.toMap(EventResponseDto::id, Function.identity()));
    }
    private EventIntentResponse.State state(EventAudienceIntent value, Actor actor, EventResponseDto event, Instant now) {
        boolean available = event != null, ended = available && ended(event, now);
        return new EventIntentResponse.State(value.getId().getEventId(), value.getIntent(), value.isPublishedOnProfile(), value.getNote(),
                value.getVersion(), value.getUpdatedAt(), available, ended, available && !ended,
                available && !ended && actor.mayPublish(), available && actor.mayPublish() && value.isPublishedOnProfile(), event, value.getPostId());
    }
    static boolean ended(EventResponseDto event, Instant now) {
        if (event == null || event.eventDate() == null || event.startTime() == null) return true;
        var start = event.eventDate().atTime(event.startTime());
        var end = event.endTime() == null || event.endTime().isBefore(event.startTime())
                ? start.plusHours(1) : event.eventDate().atTime(event.endTime());
        return !end.isAfter(LocalDateTime.ofInstant(now, EventScheduleClock.ZONE));
    }
    private Pageable page(EventIntentPeriod period, int page, int size) {
        if (period == null || page < 0 || page > 1000 || size < 1 || size > 50) throw invalid();
        return PageRequest.of(page, size);
    }
    private String validate(UUID eventId, EventIntentUpdate value) {
        if (eventId == null || eventId.equals(new UUID(0,0)) || value == null || value.intent() == null || value.publishedOnProfile() == null
                || value.expectedVersion() == null || value.expectedVersion() < 0 || value.expectedVersion() == Long.MAX_VALUE) throw invalid();
        String note = value.note() == null ? null : value.note().strip();
        if (note != null && note.isEmpty()) note = null;
        if (note != null && (note.codePointCount(0, note.length()) > 500 || note.codePoints().anyMatch(c ->
                (Character.isISOControl(c) && c != '\n' && c != '\t') || (c >= 0xd800 && c <= 0xdfff)))) throw invalid();
        if ((!value.publishedOnProfile() && note != null) || (value.intent() == EventIntent.NONE && (value.publishedOnProfile() || note != null))) throw invalid();
        return note;
    }
    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.EVENT_INTENT_INVALID); }
    private SoundConnectException forbidden() { return new SoundConnectException(ErrorType.FORBIDDEN_ACCESS); }
    private SoundConnectException eventNotFound() { return new SoundConnectException(ErrorType.EVENT_NOT_FOUND); }
    private SoundConnectException profileNotFound() { return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND); }
    private record Actor(boolean listener, EventAudienceRepository.ListenerVisibility profile) {
        boolean ghost() { return listener && "GHOST".equals(profile.getMode()); }
        boolean mayPublish() { return listener && profile.getChoiceCompleted() && "STANDARD".equals(profile.getMode()); }
    }
}
