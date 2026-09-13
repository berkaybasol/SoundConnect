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
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
    public static final String ALGORITHM_VERSION = "musician-v1.1.0";

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
            @Qualifier("musicianFeedProviderExecutor") ExecutorService providerExecutor
    ) {
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
    }

    public MusicianFeedPageResponse get(
            UUID viewerUserId,
            Integer requestedLimit,
            String cursor,
            Collection<String> advertisedTypes
    ) {
        if (!properties.isEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Set<MusicianFeedItemType> supportedTypes = parseSupportedTypes(advertisedTypes);
        int limit = requestedLimit == null ? properties.getDefaultPageSize() : requestedLimit;
        if (limit < 1 || limit > properties.getMaxPageSize()) throw badRequest();
        UUID musicianProfileId = viewerGuard.requireMusicianProfile(viewerUserId);
        Instant generatedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        boolean continuation = cursor != null && !cursor.isBlank();
        MusicianFeedCursorState replaySafeState = continuation
                ? cursors.decodeForReplay(cursor, viewerUserId, supportedTypes, generatedAt) : null;
        String requestFingerprint = continuation
                ? requestFingerprint(cursor, limit, supportedTypes) : null;
        MusicianFeedDeliverySnapshot deliverySnapshot = null;
        if (continuation) {
            Optional<MusicianFeedPageResponse> replay = deliveries.replay(viewerUserId,
                    replaySafeState.feedSessionId(), replaySafeState.deliveredItemCount(),
                    requestFingerprint, limit, supportedTypes, generatedAt);
            if (replay.isPresent()) return replay.get();
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
                if (committed.isPresent()) return committed.get();
                throw cursorInvalid();
            }
        }

        var feedbackSnapshot = feedback.snapshot(viewerUserId);
        var personalizationSnapshot = personalization.load(viewerUserId, musicianProfileId);
        String rankingContext = rankingContext(feedbackSnapshot.rankingContextVersion(), personalizationSnapshot);
        MusicianFeedCursorState state;
        if (continuation) {
            state = cursors.decode(cursor, viewerUserId, supportedTypes, generatedAt, rankingContext);
        } else {
            state = new MusicianFeedCursorState(viewerUserId, UUID.randomUUID(), generatedAt,
                    null, 0, 0, rankingContext);
            deliverySnapshot = deliveries.snapshot(viewerUserId, state.feedSessionId(), generatedAt);
        }
        int maxSession = Math.max(properties.getMaxPageSize(),
                Math.min(properties.getMaxSessionDeliveries(), 10_000));
        int remainingSessionCapacity = Math.max(0, maxSession - Math.toIntExact(state.deliveredItemCount()));
        if (remainingSessionCapacity == 0) {
            return new MusicianFeedPageResponse(SCHEMA_VERSION, ALGORITHM_VERSION, state.feedSessionId(),
                    generatedAt, List.of(), null, false);
        }
        int pageLimit = Math.min(limit, remainingSessionCapacity);
        int providerLimit = Math.max(pageLimit, Math.min(properties.getProviderLimit(), pageLimit * 8));
        var request = new MusicianFeedCandidateRequest(viewerUserId, musicianProfileId,
                state.feedSessionId(), state.anchor(), generatedAt, providerLimit,
                supportedTypes, personalizationSnapshot, feedbackSnapshot, deliverySnapshot,
                continuation ? state.announcementPlan() : null);

        CollectedCandidates collected = collectCandidates(request);
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
                deliverySnapshot.deliveredTableGroupShareCount(), announcementPlan, deliverySnapshot);
        long deliveredSize = mixed.items().size();
        boolean hasMore = !mixed.items().isEmpty() && mixed.hasMore()
                && state.deliveredItemCount() + deliveredSize < maxSession;
        String nextCursor = hasMore && mixed.cursorBoundary() != null
                ? cursors.encode(new MusicianFeedCursorState(viewerUserId, state.feedSessionId(), state.anchor(),
                        mixed.cursorBoundary(), mixed.deliveredOrganicCount(),
                        state.deliveredItemCount() + deliveredSize, rankingContext, announcementPlan), supportedTypes)
                : null;
        if (continuation) {
            return deliveries.recordPageAndReplay(viewerUserId, state.feedSessionId(), state.anchor(),
                    SCHEMA_VERSION, ALGORITHM_VERSION, state.deliveredItemCount(), requestFingerprint,
                    limit, supportedTypes, mixed.items(), mixed.itemLanes(), nextCursor, hasMore, generatedAt);
        }
        List<MusicianFeedItemResponse> deliveredItems = deliveries.recordPage(viewerUserId,
                state.feedSessionId(), state.anchor(), SCHEMA_VERSION, ALGORITHM_VERSION,
                state.deliveredItemCount(), mixed.items(), mixed.itemLanes(), generatedAt);
        return new MusicianFeedPageResponse(SCHEMA_VERSION, ALGORITHM_VERSION, state.feedSessionId(),
                generatedAt, deliveredItems, nextCursor, hasMore);
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
                    if (!task.started().get()) throw capacityUnavailable(new TimeoutException(
                            "Feed provider deadline expired before execution"));
                    if (!task.optional()) throw new IllegalStateException("Required feed provider deadline exceeded");
                    log.warn("Optional musician-feed provider exceeded global deadline: provider={}",
                            task.providerId());
                    continue;
                }
                try {
                    List<MusicianFeedCandidate> values = task.future().isDone()
                            ? task.future().get()
                            : task.future().get(remaining, TimeUnit.NANOSECONDS);
                    if (task.promotion()) promotions.addAll(values); else organic.addAll(values);
                } catch (TimeoutException timeout) {
                    task.future().cancel(true);
                    if (!task.started().get()) throw capacityUnavailable(timeout);
                    if (!task.optional()) throw new IllegalStateException("Required feed provider timed out", timeout);
                    log.warn("Optional musician-feed provider timed out: provider={}", task.providerId());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw capacityUnavailable(interrupted);
                } catch (CancellationException cancelled) {
                    throw capacityUnavailable(cancelled);
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (!task.started().get()) throw capacityUnavailable(cause);
                    if (!task.optional()) {
                        if (cause instanceof RuntimeException runtime) throw runtime;
                        throw new IllegalStateException(cause);
                    }
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
        return new CollectedCandidates(restrictions.filter(organic), restrictions.filter(promotions));
    }

    private List<MusicianFeedCandidate> required(String providerId,
                                                  Set<MusicianFeedItemType> providerTypes,
                                                  MusicianFeedCandidateRequest request,
                                                  boolean promotion,
                                                  Callable<List<MusicianFeedCandidate>> source,
                                                  int limit, long deadline) {
        try {
            List<MusicianFeedCandidate> values = MusicianFeedCandidateContract.validateBatch(
                    providerId, providerTypes, request, source.call(), limit, promotion);
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Required feed provider deadline exceeded: " + providerId);
            }
            return List.copyOf(values);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
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
                        request, source.call(), limit, promotion);
            };
            Future<List<MusicianFeedCandidate>> future = providerExecutor instanceof MusicianFeedProviderExecutor bounded
                    ? bounded.submitBefore(validated, deadline) : providerExecutor.submit(validated);
            pending.add(new Pending(providerId, optional, promotion, future, started));
        } catch (RejectedExecutionException | TimeoutException unavailable) {
            throw capacityUnavailable(unavailable);
        } catch (InterruptedException interrupted) {
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
                                  com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot value) {
        String instruments = value.instrumentIds().stream().map(UUID::toString).sorted()
                .reduce((left, right) -> left + "," + right).orElse("");
        String completion = value.completion() == null ? "" : value.completion().completed() + "/"
                + value.completion().total() + ":" + value.completion().tasks().stream()
                .sorted(Comparator.comparingInt(MusicianFeedPayloads.CompletionTask::priority)
                        .thenComparing(MusicianFeedPayloads.CompletionTask::code))
                .map(task -> task.code() + "@" + task.priority() + "@" + task.complete())
                .reduce((left, right) -> left + "," + right).orElse("");
        String canonical = feedbackVersion + "|" + value.opportunityCityId() + "|" + instruments
                + "|" + completion;
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

    private record Pending(String providerId, boolean optional, boolean promotion,
                           Future<List<MusicianFeedCandidate>> future, AtomicBoolean started) { }
    private record CollectedCandidates(List<MusicianFeedCandidate> organic,
                                       List<MusicianFeedCandidate> promotions) { }
}
