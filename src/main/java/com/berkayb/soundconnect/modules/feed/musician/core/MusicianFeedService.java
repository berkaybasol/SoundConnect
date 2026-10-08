package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.announcement.MusicianFeedAnnouncementPlan;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.cursor.*;
import com.berkayb.soundconnect.modules.feed.musician.delivery.*;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackService;
import com.berkayb.soundconnect.modules.feed.musician.mixer.MusicianFeedMixer;
import com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionGuard;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSource;
import com.berkayb.soundconnect.modules.feed.musician.sponsor.MusicianFeedSponsorshipProvider;
import com.berkayb.soundconnect.modules.feed.listener.core.ListenerFeedContentPolicy;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class MusicianFeedService {
    public static final int SCHEMA_VERSION = 1;
    public static final String ALGORITHM_VERSION = "musician-v1.2.0";

    private final MusicianFeedProperties properties;
    private final MusicianFeedViewerGuard viewerGuard;
    private final MusicianFeedCursorCodec cursors;
    private final MusicianFeedMixer mixer;
    private final MusicianFeedFeedbackService feedback;
    private final MusicianFeedRestrictionGuard restrictions;
    private final MusicianFeedPersonalizationSource personalization;
    private final MusicianFeedDeliveryService deliveries;
    private final List<MusicianFeedCandidateProvider> providers;
    private final List<MusicianFeedSponsorshipProvider> sponsorshipProviders;
    private final Clock clock;
    private final ExecutorService providerExecutor;
    private final MusicianFeedMetrics metrics;
    private ListenerFeedContentPolicy listenerContent;

    @Autowired(required = false)
    public void setListenerContentPolicy(ListenerFeedContentPolicy listenerContent) {
        this.listenerContent = Objects.requireNonNull(listenerContent);
    }

    @Autowired
    public MusicianFeedService(
            MusicianFeedProperties properties,
            MusicianFeedViewerGuard viewerGuard,
            MusicianFeedCursorCodec cursors,
            MusicianFeedMixer mixer,
            MusicianFeedFeedbackService feedback,
            MusicianFeedRestrictionGuard restrictions,
            MusicianFeedPersonalizationSource personalization,
            MusicianFeedDeliveryService deliveries,
            List<MusicianFeedCandidateProvider> providers,
            List<MusicianFeedSponsorshipProvider> sponsorshipProviders,
            @Qualifier("musicianFeedProviderExecutor") ExecutorService providerExecutor,
            MusicianFeedMetrics metrics
    ) {
        this(properties, viewerGuard, cursors, mixer, feedback, restrictions, personalization,
                deliveries, providers, sponsorshipProviders, providerExecutor, Clock.systemUTC(), metrics);
    }

    public MusicianFeedService(MusicianFeedProperties properties, MusicianFeedViewerGuard viewerGuard,
            MusicianFeedCursorCodec cursors, MusicianFeedMixer mixer, MusicianFeedFeedbackService feedback,
            MusicianFeedRestrictionGuard restrictions, MusicianFeedPersonalizationSource personalization,
            MusicianFeedDeliveryService deliveries, List<MusicianFeedCandidateProvider> providers,
            List<MusicianFeedSponsorshipProvider> sponsorshipProviders, ExecutorService providerExecutor) {
        this(properties, viewerGuard, cursors, mixer, feedback, restrictions, personalization,
                deliveries, providers, sponsorshipProviders, providerExecutor, Clock.systemUTC());
    }

    MusicianFeedService(
            MusicianFeedProperties properties,
            MusicianFeedViewerGuard viewerGuard,
            MusicianFeedCursorCodec cursors,
            MusicianFeedMixer mixer,
            MusicianFeedFeedbackService feedback,
            MusicianFeedRestrictionGuard restrictions,
            MusicianFeedPersonalizationSource personalization,
            MusicianFeedDeliveryService deliveries,
            List<MusicianFeedCandidateProvider> providers,
            List<MusicianFeedSponsorshipProvider> sponsorshipProviders,
            ExecutorService providerExecutor,
            Clock clock
    ) {
        this(properties, viewerGuard, cursors, mixer, feedback, restrictions, personalization,
                deliveries, providers, sponsorshipProviders, providerExecutor, clock, MusicianFeedMetrics.unbound());
    }

    MusicianFeedService(MusicianFeedProperties properties, MusicianFeedViewerGuard viewerGuard,
            MusicianFeedCursorCodec cursors, MusicianFeedMixer mixer, MusicianFeedFeedbackService feedback,
            MusicianFeedRestrictionGuard restrictions, MusicianFeedPersonalizationSource personalization,
            MusicianFeedDeliveryService deliveries, List<MusicianFeedCandidateProvider> providers,
            List<MusicianFeedSponsorshipProvider> sponsorshipProviders, ExecutorService providerExecutor,
            Clock clock, MusicianFeedMetrics metrics) {
        this.properties = properties;
        this.viewerGuard = viewerGuard;
        this.cursors = cursors;
        this.mixer = mixer;
        this.feedback = feedback;
        this.restrictions = restrictions;
        this.personalization = personalization;
        this.deliveries = deliveries;
        this.providers = providers.stream().sorted(Comparator.comparing(MusicianFeedCandidateProvider::providerId)).toList();
        this.sponsorshipProviders = sponsorshipProviders.stream()
                .sorted(Comparator.comparing(MusicianFeedSponsorshipProvider::providerId)).toList();
        this.clock = clock;
        this.providerExecutor = providerExecutor;
        this.metrics = Objects.requireNonNull(metrics);
        metrics.bindExecutor(providerExecutor);
    }

    public MusicianFeedPageResponse get(
            UUID viewerUserId,
            Integer requestedLimit,
            String cursor,
            Collection<String> advertisedTypes
    ) {
        return metrics.page(() -> getPage(viewerUserId, requestedLimit, cursor, advertisedTypes, BackstageFeedAudience.MUSICIAN));
    }

    public MusicianFeedPageResponse getForVenue(UUID viewerUserId, Integer requestedLimit,
                                               String cursor, Collection<String> advertisedTypes) {
        return metrics.page(() -> getPage(viewerUserId, requestedLimit, cursor, advertisedTypes, BackstageFeedAudience.VENUE));
    }

    public MusicianFeedPageResponse getForStudio(UUID viewerUserId, Integer requestedLimit,
                                                String cursor, Collection<String> advertisedTypes) {
        return metrics.page(() -> getPage(viewerUserId, requestedLimit, cursor, advertisedTypes, BackstageFeedAudience.STUDIO));
    }

    public MusicianFeedPageResponse getForListener(UUID viewerUserId, Integer requestedLimit,
                                                  String cursor, Collection<String> advertisedTypes) {
        return metrics.page(() -> getPage(viewerUserId, requestedLimit, cursor, advertisedTypes, BackstageFeedAudience.LISTENER));
    }

    private MusicianFeedPageResponse getPage(UUID viewerUserId, Integer requestedLimit,
                                            String cursor, Collection<String> advertisedTypes, BackstageFeedAudience audience) {
        if (!properties.isEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Set<MusicianFeedItemType> supportedTypes = parseSupportedTypes(advertisedTypes);
        if (audience != BackstageFeedAudience.MUSICIAN) {
            supportedTypes = supportedTypes.stream().filter(type -> type != MusicianFeedItemType.PROFILE_COMPLETION)
                    .filter(type -> audience != BackstageFeedAudience.LISTENER
                            || (type != MusicianFeedItemType.SPONSORED && type != MusicianFeedItemType.COLLAB))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (supportedTypes.isEmpty()) throw badRequest();
        }
        String algorithmVersion = audience.algorithmVersion();
        int limit = requestedLimit == null ? properties.getDefaultPageSize() : requestedLimit;
        if (limit < 1 || limit > properties.getMaxPageSize()) throw badRequest();
        UUID viewerProfileId = switch (audience) {
            case MUSICIAN -> viewerGuard.requireMusicianProfile(viewerUserId);
            case VENUE -> viewerGuard.requireVenueProfile(viewerUserId);
            case STUDIO -> viewerGuard.requireStudioProfile(viewerUserId);
            case LISTENER -> viewerGuard.requireListenerProfile(viewerUserId);
        };
        if (audience == BackstageFeedAudience.LISTENER && listenerContent == null) {
            throw new IllegalStateException("Listener feed content policy is unavailable");
        }
        Instant generatedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        boolean continuation = cursor != null && !cursor.isBlank();
        MusicianFeedCursorState replaySafeState = continuation
                ? cursors.decodeForReplay(cursor, viewerUserId, supportedTypes, generatedAt, audience, viewerProfileId) : null;
        String requestFingerprint = continuation
                ? requestFingerprint(cursor, limit, supportedTypes) : null;
        MusicianFeedDeliverySnapshot deliverySnapshot = null;
        if (continuation) {
            Optional<MusicianFeedPageResponse> replay = deliveries.replay(viewerUserId,
                    replaySafeState.feedSessionId(), replaySafeState.deliveredItemCount(),
                    requestFingerprint, limit, supportedTypes, generatedAt);
            if (replay.isPresent()) return requireAudienceReplay(viewerUserId, replay.get(), generatedAt, audience);
            deliverySnapshot = deliveries.snapshot(viewerUserId,
                    replaySafeState.feedSessionId(), generatedAt);
            if (deliverySnapshot.nextAbsolutePosition() != replaySafeState.deliveredItemCount()) {
                // A competing identical continuation can commit the ledger and replay journal
                // between our first journal lookup and this snapshot. Re-read the exact replay
                // identity before declaring the cursor stale; otherwise the loser would skip a
                // successfully committed page or surface a false cursor-refresh error.
                Optional<MusicianFeedPageResponse> committed = deliveries.replay(viewerUserId,
                        replaySafeState.feedSessionId(), replaySafeState.deliveredItemCount(),
                        requestFingerprint, limit, supportedTypes, generatedAt);
                if (committed.isPresent()) return requireAudienceReplay(viewerUserId, committed.get(), generatedAt, audience);
                throw cursorInvalid();
            }
        }

        var feedbackSnapshot = feedback.snapshot(viewerUserId);
        var personalizationSnapshot = switch (audience) {
            case MUSICIAN -> personalization.load(viewerUserId, viewerProfileId);
            case VENUE -> personalization.loadForVenue(viewerUserId, viewerProfileId);
            case STUDIO -> personalization.loadForStudio(viewerUserId, viewerProfileId);
            case LISTENER -> personalization.loadForListener(viewerUserId, viewerProfileId);
        };
        String rankingContext = rankingContext(feedbackSnapshot.rankingContextVersion(), personalizationSnapshot,
                audience, viewerProfileId);
        MusicianFeedCursorState state;
        if (continuation) {
            state = cursors.decode(cursor, viewerUserId, supportedTypes, generatedAt, rankingContext, audience, viewerProfileId);
        } else {
            state = new MusicianFeedCursorState(viewerUserId, UUID.randomUUID(), generatedAt,
                    null, 0, 0, rankingContext, MusicianFeedAnnouncementPlan.EMPTY, audience, viewerProfileId);
            deliverySnapshot = deliveries.snapshot(viewerUserId, state.feedSessionId(), generatedAt);
        }
        int maxSession = Math.max(properties.getMaxPageSize(),
                Math.min(properties.getMaxSessionDeliveries(), 10_000));
        int remainingSessionCapacity = Math.max(0, maxSession - Math.toIntExact(state.deliveredItemCount()));
        if (remainingSessionCapacity == 0) {
            return new MusicianFeedPageResponse(SCHEMA_VERSION, algorithmVersion, state.feedSessionId(),
                    generatedAt, List.of(), null, false);
        }
        int pageLimit = Math.min(limit, remainingSessionCapacity);
        int providerLimit = Math.max(pageLimit, Math.min(properties.getProviderLimit(), pageLimit * 8));
        var request = new MusicianFeedCandidateRequest(viewerUserId, viewerProfileId,
                state.feedSessionId(), state.anchor(), generatedAt, providerLimit,
                supportedTypes, personalizationSnapshot, feedbackSnapshot, deliverySnapshot,
                continuation ? state.announcementPlan() : null).withAudience(audience);

        CollectedCandidates collected = collectCandidates(request);
        if (audience == BackstageFeedAudience.LISTENER) {
            collected = new CollectedCandidates(
                    listenerContent.filterCandidates(viewerUserId, collected.organic()),
                    listenerContent.filterCandidates(viewerUserId, collected.promotions()),
                    collected.organicSourceUnavailable());
        }
        // Use only qualified views recorded before this session's fixed anchor. Prefetch and
        // views from this session must not change continuation ranking underneath its cursor.
        Set<String> candidateTargets = collected.organic().stream()
                .filter(value -> value.type() != MusicianFeedItemType.ANNOUNCEMENT
                        && value.type() != MusicianFeedItemType.PROFILE_COMPLETION)
                .map(value -> MusicianFeedDeliverySnapshot.targetKey(value.target().type(), value.target().id()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        try {
            deliverySnapshot = deliverySnapshot.withRecentlyViewedTargetKeys(deliveries.recentlyViewedTargetKeys(
                    viewerUserId, state.feedSessionId(), state.anchor(), candidateTargets));
        } catch (DataAccessException | TransactionException unavailable) {
            // This is soft ranking enrichment. A failed history read must not discard valid content.
            metrics.historyUnavailable();
            log.warn("Musician-feed recent-view history unavailable; using candidate ranking", unavailable);
        }
        var candidateFeedback = feedback.forCandidates(viewerUserId, feedbackSnapshot,
                collected.organic(), collected.promotions());
        MusicianFeedAnnouncementPlan announcementPlan = continuation ? state.announcementPlan()
                : new MusicianFeedAnnouncementPlan(collected.organic().stream()
                .filter(value -> value.type() == MusicianFeedItemType.ANNOUNCEMENT)
                .map(MusicianFeedCandidate::announcementPlacement).toList());
        MusicianFeedMixer.MixedPage mixed = mixer.mix(viewerUserId, state.anchor(), pageLimit, supportedTypes,
                candidateFeedback, collected.organic(), collected.promotions(), null,
                state.deliveredOrganicCount(), deliverySnapshot.deliveredPromotionCount(),
                deliverySnapshot.lastItemPromoted(), deliverySnapshot.lastItemType(),
                deliverySnapshot.lastItemLane(),
                deliverySnapshot.organicCountAtLastPromotion(),
                deliverySnapshot.deliveredOverthinkingShareCount(),
                deliverySnapshot.deliveredTableGroupShareCount(), announcementPlan, deliverySnapshot, audience);
        long deliveredSize = mixed.items().size();
        boolean hasMore = !mixed.items().isEmpty() && mixed.hasMore()
                && state.deliveredItemCount() + deliveredSize < maxSession;
        if (collected.organicSourceUnavailable() && !hasMore
                && state.deliveredItemCount() + deliveredSize < maxSession) {
            // A failed content source cannot prove exhaustion. Keep this position
            // unwritten so the same cursor can recover after the source recovers.
            // Healthy partial pages with a real continuation still make progress;
            // the explicit session capacity remains an independent terminal bound.
            throw capacityUnavailable(new IllegalStateException(
                    "Cannot confirm feed exhaustion while an organic content source is unavailable"));
        }
        String nextCursor = hasMore && mixed.cursorBoundary() != null
                ? cursors.encode(new MusicianFeedCursorState(viewerUserId, state.feedSessionId(), state.anchor(),
                        mixed.cursorBoundary(), mixed.deliveredOrganicCount(),
                        state.deliveredItemCount() + deliveredSize, rankingContext, announcementPlan, audience, viewerProfileId), supportedTypes)
                : null;
        if (continuation) {
            MusicianFeedPageResponse page = deliveries.recordPageAndReplay(viewerUserId, state.feedSessionId(), state.anchor(),
                    SCHEMA_VERSION, algorithmVersion, state.deliveredItemCount(), requestFingerprint,
                    limit, supportedTypes, mixed.items(), mixed.itemLanes(), nextCursor, hasMore, generatedAt);
            return requireAudienceReplay(viewerUserId, page, generatedAt, audience);
        }
        List<MusicianFeedItemResponse> deliveredItems = deliveries.recordPage(viewerUserId,
                state.feedSessionId(), state.anchor(), SCHEMA_VERSION, algorithmVersion,
                state.deliveredItemCount(), mixed.items(), mixed.itemLanes(), generatedAt);
        MusicianFeedPageResponse page = new MusicianFeedPageResponse(SCHEMA_VERSION, algorithmVersion, state.feedSessionId(),
                generatedAt, deliveredItems, nextCursor, hasMore);
        return page;
    }

    private MusicianFeedPageResponse requireAudienceReplay(UUID viewerUserId, MusicianFeedPageResponse page,
                                                           Instant now, BackstageFeedAudience audience) {
        if (audience == BackstageFeedAudience.LISTENER) listenerContent.requireReplayEligible(viewerUserId, page, now);
        return page;
    }

    public Set<MusicianFeedItemType> parseSupportedTypes(Collection<String> advertisedTypes) {
        if (advertisedTypes == null || advertisedTypes.isEmpty()) throw badRequest();
        EnumSet<MusicianFeedItemType> result = EnumSet.noneOf(MusicianFeedItemType.class);
        for (String value : advertisedTypes) {
            if (value == null) continue;
            for (String token : value.split(",", -1)) {
                if (token.isBlank()) continue;
                try {
                    result.add(MusicianFeedItemType.valueOf(token.strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException unknownToThisServerVersion) {
                    // Rolling clients may advertise renderers introduced by a
                    // newer backend. Serve only the known intersection.
                }
            }
        }
        if (result.isEmpty()) throw badRequest();
        return Set.copyOf(result);
    }

    private CollectedCandidates collectCandidates(MusicianFeedCandidateRequest originalRequest) {
        List<Pending> pending = new ArrayList<>();
        List<MusicianFeedCandidate> organic = new ArrayList<>();
        List<MusicianFeedCandidate> promotions = new ArrayList<>();
        boolean organicSourceUnavailable = false;
        long deadline = System.nanoTime() + properties.getProviderDeadline().toNanos();
        MusicianFeedCandidateRequest request = originalRequest.withProviderDeadline(deadline);
        try {
            for (MusicianFeedCandidateProvider provider : providers.stream()
                    .sorted(Comparator.comparing(MusicianFeedCandidateProvider::optional)
                            .thenComparing(MusicianFeedCandidateProvider::providerId)).toList()) {
                Set<MusicianFeedItemType> providerTypes = provider.supportedTypes();
                if (Collections.disjoint(providerTypes, request.supportedTypes())) continue;
                if (provider.optional()) {
                    submit(pending, provider.providerId(), providerTypes, request, true, false,
                            () -> provider.findCandidates(request), request.limit(), deadline);
                } else {
                    organic.addAll(required(provider.providerId(), providerTypes, request, false,
                            () -> provider.findCandidates(request), request.limit(), deadline));
                }
            }
            for (MusicianFeedSponsorshipProvider provider : sponsorshipProviders.stream()
                    .sorted(Comparator.comparing(MusicianFeedSponsorshipProvider::optional)
                            .thenComparing(MusicianFeedSponsorshipProvider::providerId)).toList()) {
                Set<MusicianFeedItemType> providerTypes = provider.supportedTypes();
                if (Collections.disjoint(providerTypes, request.supportedTypes())) continue;
                if (provider.optional()) {
                    submit(pending, provider.providerId(), providerTypes, request, true, true,
                            () -> provider.findPlacements(request), request.limit(), deadline);
                } else {
                    promotions.addAll(required(provider.providerId(), providerTypes, request, true,
                            () -> provider.findPlacements(request), request.limit(), deadline));
                }
            }
            for (Pending task : pending) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 && !task.future().isDone()) {
                    task.future().cancel(true);
                    metrics.providerResult(task.providerId(), task.started().get() ? "timeout" : "capacity");
                    if (!task.started().get()) throw capacityUnavailable(new TimeoutException(
                            "Feed provider deadline expired before execution"));
                    if (!task.optional()) throw new IllegalStateException("Required feed provider deadline exceeded");
                    organicSourceUnavailable |= task.organicContent();
                    log.warn("Optional musician-feed provider exceeded global deadline: provider={}",
                            task.providerId());
                    continue;
                }
                try {
                    List<MusicianFeedCandidate> values = task.future().isDone()
                            ? task.future().get()
                            : task.future().get(remaining, TimeUnit.NANOSECONDS);
                    metrics.providerResult(task.providerId(), "success");
                    if (task.promotion()) promotions.addAll(values); else organic.addAll(values);
                } catch (TimeoutException timeout) {
                    task.future().cancel(true);
                    metrics.providerResult(task.providerId(), task.started().get() ? "timeout" : "capacity");
                    if (!task.started().get()) throw capacityUnavailable(timeout);
                    if (!task.optional()) throw new IllegalStateException("Required feed provider timed out", timeout);
                    organicSourceUnavailable |= task.organicContent();
                    log.warn("Optional musician-feed provider timed out: provider={}", task.providerId());
                } catch (InterruptedException interrupted) {
                    metrics.providerResult(task.providerId(), "interrupted");
                    Thread.currentThread().interrupt();
                    throw capacityUnavailable(interrupted);
                } catch (CancellationException cancelled) {
                    metrics.providerResult(task.providerId(), "cancelled");
                    throw capacityUnavailable(cancelled);
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    metrics.providerResult(task.providerId(), task.started().get() ? "failure" : "capacity");
                    if (!task.started().get()) throw capacityUnavailable(cause);
                    if (!task.optional()) {
                        if (cause instanceof RuntimeException runtime) throw runtime;
                        throw new IllegalStateException(cause);
                    }
                    organicSourceUnavailable |= task.organicContent();
                    log.warn("Optional musician-feed provider failed closed: provider={}",
                            task.providerId(), cause);
                }
            }
        } finally {
            // A later admission/required-provider failure must also stop earlier jobs.
            // Removing cancelled queue entries promptly restores capacity for other pages.
            for (Pending task : pending) {
                if (!task.started().get()) task.future().cancel(true);
            }
            for (Pending task : pending) {
                if (!task.future().isDone()) task.future().cancel(true);
                if (task.future().isCancelled() && providerExecutor instanceof ThreadPoolExecutor pool
                        && task.future() instanceof Runnable queued) pool.remove(queued);
            }
        }
        organic.removeIf(candidate -> candidate.promotion() != null
                || request.delivery().itemIds().contains(candidate.itemId())
                || (candidate.type() != MusicianFeedItemType.ACTIVITY_COMMENT
                    && request.delivery().organicTargetKeys().contains(
                    MusicianFeedDeliverySnapshot.targetKey(candidate.target().type(), candidate.target().id())))
                || request.delivery().promotedTargetKeys().contains(
                MusicianFeedDeliverySnapshot.targetKey(candidate.target().type(), candidate.target().id())));
        promotions.removeIf(candidate -> candidate.promotion() == null
                || !request.supportedTypes().contains(candidate.type())
                || request.delivery().itemIds().contains(candidate.itemId())
                || request.delivery().targetKeys().contains(
                MusicianFeedDeliverySnapshot.targetKey(candidate.target().type(), candidate.target().id())));
        var result = new CollectedCandidates(restrictions.filter(organic), restrictions.filter(promotions),
                organicSourceUnavailable);
        metrics.candidates(result.organic());
        metrics.candidates(result.promotions());
        return result;
    }

    private List<MusicianFeedCandidate> required(String providerId,
                                                  Set<MusicianFeedItemType> providerTypes,
                                                  MusicianFeedCandidateRequest request,
                                                  boolean promotion,
                                                  Callable<List<MusicianFeedCandidate>> source,
                                                  int limit, long deadline) {
        try {
            List<MusicianFeedCandidate> values = MusicianFeedCandidateContract.validateBatch(
                    providerId, providerTypes, request, metrics.execute(providerId, source), limit, promotion);
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Required feed provider deadline exceeded: " + providerId);
            }
            metrics.providerResult(providerId, "success");
            return List.copyOf(values);
        } catch (RuntimeException failure) {
            metrics.providerResult(providerId, "failure");
            throw failure;
        } catch (Exception failure) {
            metrics.providerResult(providerId, "failure");
            throw new IllegalStateException("Required feed provider failed: " + providerId, failure);
        }
    }

    private void submit(List<Pending> pending, String providerId,
                        Set<MusicianFeedItemType> providerTypes,
                        MusicianFeedCandidateRequest request,
                        boolean optional, boolean promotion,
                        Callable<List<MusicianFeedCandidate>> source, int limit, long deadline) {
        try {
            AtomicBoolean started = new AtomicBoolean();
            Callable<List<MusicianFeedCandidate>> validated = () -> {
                if (deadline - System.nanoTime() <= 0) {
                    throw new TimeoutException("Feed provider deadline expired before execution");
                }
                started.set(true);
                return MusicianFeedCandidateContract.validateBatch(providerId, providerTypes,
                        request, metrics.execute(providerId, source), limit, promotion);
            };
            Future<List<MusicianFeedCandidate>> future = providerExecutor instanceof MusicianFeedProviderExecutor bounded
                    ? bounded.submitBefore(validated, deadline) : providerExecutor.submit(validated);
            boolean organicContent = !promotion && providerTypes.stream()
                    .filter(request.supportedTypes()::contains)
                    .anyMatch(type -> type != MusicianFeedItemType.ANNOUNCEMENT
                            && type != MusicianFeedItemType.PROFILE_COMPLETION
                            && type != MusicianFeedItemType.SPONSORED);
            pending.add(new Pending(providerId, optional, promotion, organicContent, future, started));
        } catch (RejectedExecutionException | TimeoutException unavailable) {
            metrics.providerResult(providerId, "capacity");
            throw capacityUnavailable(unavailable);
        } catch (InterruptedException interrupted) {
            metrics.providerResult(providerId, "interrupted");
            Thread.currentThread().interrupt();
            throw capacityUnavailable(interrupted);
        }
    }

    private SoundConnectException capacityUnavailable(Throwable cause) {
        SoundConnectException failure = new SoundConnectException(ErrorType.MUSICIAN_FEED_CAPACITY_UNAVAILABLE);
        failure.initCause(cause);
        return failure;
    }

    private String rankingContext(String feedbackVersion,
                                  com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot value,
                                  BackstageFeedAudience audience, UUID viewerProfileId) {
        String instruments = value.instrumentIds().stream().map(UUID::toString).sorted()
                .reduce((left, right) -> left + "," + right).orElse("");
        String completion = value.completion() == null ? "" : value.completion().completed() + "/"
                + value.completion().total() + ":" + value.completion().tasks().stream()
                .sorted(Comparator.comparingInt(MusicianFeedPayloads.CompletionTask::priority)
                        .thenComparing(MusicianFeedPayloads.CompletionTask::code))
                .map(task -> task.code() + "@" + task.priority() + "@" + task.complete())
                .reduce((left, right) -> left + "," + right).orElse("");
        String canonical = ALGORITHM_VERSION + "|" + feedbackVersion + "|" + value.opportunityCityId() + "|" + instruments
                + "|" + completion;
        if (audience != BackstageFeedAudience.MUSICIAN) {
            canonical = audience.algorithmVersion() + "|" + audience.name() + "|" + viewerProfileId + "|" + canonical;
        }
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String requestFingerprint(String cursor, int limit, Set<MusicianFeedItemType> supportedTypes) {
        String canonical = cursor + "\u0000" + limit + "\u0000" + cursors.supportedTypesHash(supportedTypes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private SoundConnectException badRequest() {
        return new SoundConnectException(ErrorType.BAD_REQUEST);
    }

    private SoundConnectException cursorInvalid() {
        return new SoundConnectException(ErrorType.MUSICIAN_FEED_CURSOR_INVALID);
    }

    private record Pending(String providerId, boolean optional, boolean promotion, boolean organicContent,
                           Future<List<MusicianFeedCandidate>> future, AtomicBoolean started) { }
    private record CollectedCandidates(List<MusicianFeedCandidate> organic,
                                       List<MusicianFeedCandidate> promotions, boolean organicSourceUnavailable) { }
}
