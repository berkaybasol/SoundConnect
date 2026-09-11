package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveredItem;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MusicianFeedFeedbackService {
    private static final int MAX_ROWS_PER_VIEWER = 5_000;
    private static final Set<MusicianFeedFeedbackAction> ITEM_ACTIONS = Set.of(
            MusicianFeedFeedbackAction.HIDE,
            MusicianFeedFeedbackAction.SHOW_LESS,
            MusicianFeedFeedbackAction.REPORT);

    private final MusicianFeedFeedbackRepository repository;
    private final MusicianFeedViewerGuard viewerGuard;
    private final MusicianFeedAuthorProfileGuard authorProfiles;
    private final MusicianFeedDeliveryService deliveries;
    private final MusicianFeedReportDispatcher reports;
    private final MusicianFeedFeedbackLock feedbackLock;
    private final Clock clock;

    @Autowired
    public MusicianFeedFeedbackService(
            MusicianFeedFeedbackRepository repository,
            MusicianFeedViewerGuard viewerGuard,
            MusicianFeedAuthorProfileGuard authorProfiles,
            MusicianFeedDeliveryService deliveries,
            MusicianFeedReportDispatcher reports,
            MusicianFeedFeedbackLock feedbackLock
    ) {
        this(repository, viewerGuard, authorProfiles, deliveries, reports, feedbackLock, Clock.systemUTC());
    }

    MusicianFeedFeedbackService(
            MusicianFeedFeedbackRepository repository,
            MusicianFeedViewerGuard viewerGuard,
            MusicianFeedAuthorProfileGuard authorProfiles,
            MusicianFeedDeliveryService deliveries,
            MusicianFeedReportDispatcher reports,
            MusicianFeedFeedbackLock feedbackLock,
            Clock clock
    ) {
        this.repository = repository;
        this.viewerGuard = viewerGuard;
        this.authorProfiles = authorProfiles;
        this.deliveries = deliveries;
        this.reports = reports;
        this.feedbackLock = feedbackLock;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MusicianFeedFeedbackSnapshot snapshot(UUID viewerUserId) {
        if (viewerUserId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        List<MusicianFeedFeedback> rows = repository.findAllByViewerUserId(viewerUserId);
        Set<String> hidden = rows.stream()
                .filter(value -> value.getAction() == MusicianFeedFeedbackAction.HIDE
                        || value.getAction() == MusicianFeedFeedbackAction.REPORT)
                .map(MusicianFeedFeedback::getItemId).filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> muted = rows.stream()
                .filter(value -> value.getAction() == MusicianFeedFeedbackAction.MUTE_AUTHOR)
                .filter(value -> value.getAuthorProfileType() != null && value.getAuthorProfileId() != null)
                .map(value -> MusicianFeedFeedbackSnapshot.authorKey(
                        value.getAuthorProfileType(), value.getAuthorProfileId()))
                .collect(Collectors.toUnmodifiableSet());
        EnumMap<MusicianFeedItemType, Integer> showLess = new EnumMap<>(MusicianFeedItemType.class);
        rows.stream().filter(value -> value.getAction() == MusicianFeedFeedbackAction.SHOW_LESS)
                .map(MusicianFeedFeedback::getItemType).filter(Objects::nonNull)
                .forEach(type -> showLess.merge(type, 1, Integer::sum));
        return new MusicianFeedFeedbackSnapshot(hidden, muted, showLess, rankingVersion(rows));
    }

    @Transactional
    public MusicianFeedFeedbackResponse recordItem(
            UUID viewerUserId,
            String itemId,
            MusicianFeedFeedbackRequest request
    ) {
        viewerGuard.requireMusicianProfile(viewerUserId);
        if (request == null || !ITEM_ACTIONS.contains(request.action())
                || request.impressionToken() == null || request.impressionToken().isBlank()) throw invalid();
        Instant now = now();
        MusicianFeedDeliveredItem delivery = deliveries.require(
                request.impressionToken(), viewerUserId, itemId, now);
        if (!delivery.feedbackCapabilities().contains(request.action())) throw invalid();
        MusicianFeedItemType itemType = delivery.itemType();
        String normalizedReason = normalizeReason(request.reason());
        String scope = "ITEM:" + itemId;
        feedbackLock.viewer(viewerUserId);
        if (request.action() == MusicianFeedFeedbackAction.REPORT) {
            reports.report(viewerUserId, delivery, normalizedReason, now);
        }
        MusicianFeedFeedback value = repository
                .findByViewerUserIdAndActionAndScopeKey(viewerUserId, request.action(), scope)
                .map(existing -> {
                    existing.updateReason(normalizedReason, now);
                    return existing;
                })
                .orElseGet(() -> {
                    assertCapacity(viewerUserId);
                    return MusicianFeedFeedback.item(viewerUserId, request.action(), itemId,
                            itemType, delivery.deliveryId(), normalizedReason, now);
                });
        return response(repository.save(value));
    }

    @Transactional
    public MusicianFeedFeedbackResponse mute(UUID viewerUserId, String profileType, UUID profileId) {
        viewerGuard.requireMusicianProfile(viewerUserId);
        String normalizedType = authorProfiles.requireEligibleNotOwned(viewerUserId, profileType, profileId);
        feedbackLock.viewer(viewerUserId);
        String scope = "AUTHOR:" + normalizedType + ":" + profileId;
        MusicianFeedFeedback value = repository
                .findByViewerUserIdAndActionAndScopeKey(viewerUserId,
                        MusicianFeedFeedbackAction.MUTE_AUTHOR, scope)
                .orElseGet(() -> {
                    assertCapacity(viewerUserId);
                    return MusicianFeedFeedback.mute(viewerUserId, normalizedType, profileId, now());
                });
        return response(repository.save(value));
    }

    @Transactional
    public void unmute(UUID viewerUserId, String profileType, UUID profileId) {
        viewerGuard.requireMusicianProfile(viewerUserId);
        String normalizedType = authorProfiles.normalize(profileType, profileId);
        feedbackLock.viewer(viewerUserId);
        repository.deleteByViewerUserIdAndActionAndScopeKey(viewerUserId,
                MusicianFeedFeedbackAction.MUTE_AUTHOR,
                "AUTHOR:" + normalizedType + ":" + profileId);
    }

    private MusicianFeedFeedbackResponse response(MusicianFeedFeedback value) {
        return new MusicianFeedFeedbackResponse(value.getId(), value.getAction(),
                value.getItemId(), value.getAuthorProfileType(), value.getAuthorProfileId(), value.getUpdatedAt());
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String value = reason.strip();
        if (value.codePointCount(0, value.length()) > 500 || value.codePoints().anyMatch(codePoint ->
                (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t')
                        || (codePoint >= 0xd800 && codePoint <= 0xdfff))) throw invalid();
        return value;
    }

    private void assertCapacity(UUID viewerUserId) {
        if (repository.countByViewerUserId(viewerUserId) >= MAX_ROWS_PER_VIEWER) throw invalid();
    }

    private String rankingVersion(List<MusicianFeedFeedback> rows) {
        String canonical = rows.stream()
                .filter(value -> value.getAction() == MusicianFeedFeedbackAction.SHOW_LESS)
                .sorted(Comparator.comparing(MusicianFeedFeedback::getScopeKey)
                        .thenComparing(MusicianFeedFeedback::getUpdatedAt))
                .map(value -> value.getScopeKey() + "@" + value.getUpdatedAt())
                .collect(Collectors.joining("|"));
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.BAD_REQUEST); }
}
