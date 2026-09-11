package com.berkayb.soundconnect.modules.feed.musician.candidate;

import com.berkayb.soundconnect.modules.feed.musician.api.*;

import java.net.URI;
import java.util.*;

/**
 * Trust boundary for candidate-provider extensions. A batch is validated in
 * full before any value reaches ranking so optional providers cannot partially
 * poison a page.
 */
public final class MusicianFeedCandidateContract {
    private static final int MAX_ITEM_ID = 256;
    private static final int MAX_TARGET_TYPE = 48;
    private static final int MAX_PROFILE_TYPE = 24;
    private static final int MAX_USERNAME = 64;
    private static final int MAX_DISPLAY_NAME = 255;
    private static final int MAX_URL = 2_048;
    private static final int MAX_CTA_LABEL = 80;
    private static final int MAX_DISCLOSURE = 64;
    private static final int MAX_VISIBLE_ACTORS = 3;
    private static final Set<String> PROFILE_TYPES = Set.of(
            "MUSICIAN", "LISTENER", "STUDIO", "VENUE", "BAND");
    private static final Set<String> TARGET_TYPES = Set.of(
            "MEDIA", "COLLAB", "EVENT", "EVENT_POST", "OVERTHINKING_PROFILE_SHARE",
            "TABLE_GROUP_POST", "PROFILE", "STANDALONE");
    private static final Set<MusicianFeedFeedbackAction> ITEM_FEEDBACK = Set.of(
            MusicianFeedFeedbackAction.HIDE,
            MusicianFeedFeedbackAction.SHOW_LESS,
            MusicianFeedFeedbackAction.REPORT);

    private MusicianFeedCandidateContract() { }

    public static List<MusicianFeedCandidate> validateBatch(
            String providerId,
            Set<MusicianFeedItemType> declaredTypes,
            MusicianFeedCandidateRequest request,
            List<MusicianFeedCandidate> values,
            int limit,
            boolean promotionSource
    ) {
        if (!text(providerId, 128) || declaredTypes == null || declaredTypes.isEmpty()
                || declaredTypes.stream().anyMatch(Objects::isNull) || request == null || values == null
                || values.size() > limit) throw invalid(providerId);
        for (MusicianFeedCandidate candidate : values) {
            validateCandidate(providerId, declaredTypes, request, candidate, promotionSource);
        }
        return List.copyOf(values);
    }

    private static void validateCandidate(
            String providerId,
            Set<MusicianFeedItemType> declaredTypes,
            MusicianFeedCandidateRequest request,
            MusicianFeedCandidate candidate,
            boolean promotionSource
    ) {
        if (candidate == null || candidate.type() == null
                || !declaredTypes.contains(candidate.type())
                || !request.supportedTypes().contains(candidate.type())
                || !text(candidate.itemId(), MAX_ITEM_ID)
                || !candidate.itemId().startsWith(candidate.type().name() + ":")
                || candidate.payloadVersion() != 1 || candidate.occurredAt() == null
                || candidate.occurredAt().isAfter(request.anchor())
                || candidate.reason() == null || candidate.reason().code() == null
                || candidate.reason().secondaryActorCount() < 0
                || candidate.reason().actors() == null
                || candidate.reason().actors().size() > MAX_VISIBLE_ACTORS
                || candidate.target() == null || candidate.target().id() == null
                || !text(candidate.target().type(), MAX_TARGET_TYPE)
                || !TARGET_TYPES.contains(candidate.target().type())
                || candidate.payload() == null || candidate.lane() == null) {
            throw invalid(providerId);
        }
        candidate.reason().actors().forEach(actor -> validateAuthor(providerId, actor));
        if (candidate.author() != null) validateAuthor(providerId, candidate.author());
        if (requiresAuthor(candidate.type()) && candidate.author() == null) throw invalid(providerId);
        validateFeedback(providerId, candidate.feedbackCapabilities());
        validateEngagement(providerId, candidate.target(), candidate.engagement());
        validatePromotion(providerId, candidate, promotionSource);
        validatePayload(providerId, candidate.type(), candidate.payload(), request.supportedTypes());
        UUID payloadTarget = payloadTarget(candidate.type(), candidate.payload());
        if (payloadTarget != null && !payloadTarget.equals(candidate.target().id())) throw invalid(providerId);
        if (candidate.type() == MusicianFeedItemType.PROFILE_COMPLETION
                && candidate.lane() != MusicianFeedLane.SYSTEM) throw invalid(providerId);
        if ((candidate.type() == MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE
                || candidate.type() == MusicianFeedItemType.TABLEGROUP_PROFILE_SHARE)
                && candidate.lane() != MusicianFeedLane.MODULE_SHARE) throw invalid(providerId);
    }

    private static boolean requiresAuthor(MusicianFeedItemType type) {
        return type != MusicianFeedItemType.PROFILE_COMPLETION
                && type != MusicianFeedItemType.SPONSORED;
    }

    private static void validateAuthor(String providerId, MusicianFeedItemResponse.Author author) {
        if (author == null || author.userId() == null || author.profileId() == null
                || !text(author.profileType(), MAX_PROFILE_TYPE)
                || !PROFILE_TYPES.contains(author.profileType())
                || !text(author.username(), MAX_USERNAME)
                || !text(author.displayName(), MAX_DISPLAY_NAME)
                || !optionalText(author.avatarUrl(), MAX_URL)) throw invalid(providerId);
    }

    private static void validateFeedback(String providerId,
                                         List<MusicianFeedFeedbackAction> capabilities) {
        if (capabilities == null || capabilities.size() > ITEM_FEEDBACK.size()
                || capabilities.stream().anyMatch(Objects::isNull)
                || !ITEM_FEEDBACK.containsAll(capabilities)
                || new HashSet<>(capabilities).size() != capabilities.size()) throw invalid(providerId);
    }

    private static void validateEngagement(String providerId,
                                           MusicianFeedItemResponse.Target target,
                                           MusicianFeedItemResponse.Engagement engagement) {
        if (engagement == null) return;
        if (!text(engagement.targetType(), MAX_TARGET_TYPE)
                || !TARGET_TYPES.contains(engagement.targetType())
                || engagement.targetId() == null || engagement.likeCount() < 0
                || engagement.commentCount() < 0
                || !engagement.targetType().equals(target.type())
                || !engagement.targetId().equals(target.id())) throw invalid(providerId);
    }

    private static void validatePromotion(String providerId,
                                          MusicianFeedCandidate candidate,
                                          boolean promotionSource) {
        MusicianFeedItemResponse.Promotion promotion = candidate.promotion();
        if (promotionSource != (promotion != null)) throw invalid(providerId);
        if (promotion == null) {
            if (candidate.type() == MusicianFeedItemType.SPONSORED
                    || isPromotionReason(candidate.reason().code())) throw invalid(providerId);
            return;
        }
        String expectedDisclosure = switch (candidate.reason().code()) {
            case SPONSORED -> "Sponsored";
            case FEATURED -> "Featured";
            case PLATFORM_ANNOUNCEMENT -> "Platform announcement";
            default -> null;
        };
        if (promotion.campaignId() == null || expectedDisclosure == null
                || !expectedDisclosure.equals(promotion.disclosure())
                || !text(promotion.disclosure(), MAX_DISCLOSURE)
                || !text(promotion.ctaLabel(), MAX_CTA_LABEL)
                || !safeCtaUrl(promotion.ctaUrl())) throw invalid(providerId);
    }

    private static boolean isPromotionReason(MusicianFeedReasonCode reason) {
        return reason == MusicianFeedReasonCode.SPONSORED
                || reason == MusicianFeedReasonCode.FEATURED
                || reason == MusicianFeedReasonCode.PLATFORM_ANNOUNCEMENT;
    }

    private static void validatePayload(String providerId, MusicianFeedItemType type, Object payload,
                                        Set<MusicianFeedItemType> supportedTypes) {
        boolean valid = switch (type) {
            case TRACK -> payload instanceof MusicianFeedPayloads.Track value
                    && value.trackId() != null && value.mediaAssetId() != null
                    && text(value.playbackUrl(), MAX_URL);
            case PROFILE_MEDIA -> payload instanceof MusicianFeedPayloads.ProfileMedia value
                    && value.mediaAssetId() != null && text(value.kind(), 32)
                    && (text(value.displayUrl(), MAX_URL) || text(value.playbackUrl(), MAX_URL));
            case COLLAB -> payload instanceof MusicianFeedPayloads.Collab value
                    && value.listing() != null && value.listing().id() != null;
            case EVENT, EVENT_PROFILE_SHARE -> payload instanceof MusicianFeedPayloads.Event value
                    && value.event() != null && value.event().id() != null;
            case OVERTHINKING_PROFILE_SHARE, TABLEGROUP_PROFILE_SHARE ->
                    payload instanceof MusicianFeedPayloads.ProfileShare value
                            && value.shareId() != null && value.publishedAt() != null
                            && value.source() != null;
            case PROFILE -> payload instanceof MusicianFeedPayloads.Profile value
                    && value.profileId() != null && value.userId() != null
                    && PROFILE_TYPES.contains(value.profileType())
                    && text(value.username(), MAX_USERNAME)
                    && text(value.displayName(), MAX_DISPLAY_NAME);
            case ACTIVITY_FOLLOW, ACTIVITY_LIKE, ACTIVITY_COMMENT ->
                    validActivity(providerId, type, payload, supportedTypes);
            case PROFILE_COMPLETION -> payload instanceof MusicianFeedPayloads.Completion value
                    && validCompletion(value);
            case SPONSORED -> payload instanceof MusicianFeedPayloads.SponsoredStandalone value
                    && text(value.title(), 255) && text(value.body(), 2_000)
                    && optionalText(value.mediaUrl(), MAX_URL)
                    && text(value.ctaLabel(), MAX_CTA_LABEL) && safeCtaUrl(value.ctaUrl());
        };
        if (!valid) throw invalid(providerId);
    }

    private static boolean validActivity(String providerId, MusicianFeedItemType outerType,
                                         Object payload, Set<MusicianFeedItemType> supportedTypes) {
        if (!(payload instanceof MusicianFeedPayloads.Activity value)
                || value.actor() == null || value.targetItemType() == null
                || value.targetPayload() == null || !supportedTypes.contains(value.targetItemType())) return false;
        String expectedAction = switch (outerType) {
            case ACTIVITY_FOLLOW -> "FOLLOW";
            case ACTIVITY_LIKE -> "LIKE";
            case ACTIVITY_COMMENT -> "COMMENT";
            default -> null;
        };
        if (!Objects.equals(expectedAction, value.action())) return false;
        validateAuthor(providerId, value.actor());
        if (value.targetItemType() == MusicianFeedItemType.ACTIVITY_FOLLOW
                || value.targetItemType() == MusicianFeedItemType.ACTIVITY_LIKE
                || value.targetItemType() == MusicianFeedItemType.ACTIVITY_COMMENT
                || value.targetItemType() == MusicianFeedItemType.PROFILE_COMPLETION
                || value.targetItemType() == MusicianFeedItemType.SPONSORED) return false;
        try {
            validatePayload(providerId, value.targetItemType(), value.targetPayload(), supportedTypes);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean validCompletion(MusicianFeedPayloads.Completion value) {
        if (value.completed() < 0 || value.total() < 0 || value.completed() > value.total()
                || value.tasks() == null || value.tasks().size() > 16) return false;
        Set<String> codes = new HashSet<>();
        for (MusicianFeedPayloads.CompletionTask task : value.tasks()) {
            if (task == null || !text(task.code(), 64) || !codes.add(task.code())
                    || !text(task.title(), 160) || !text(task.description(), 500)
                    || !text(task.ctaLabel(), MAX_CTA_LABEL) || !safeCtaUrl(task.route())
                    || task.priority() < 0 || task.priority() > 100) return false;
        }
        return true;
    }

    private static UUID payloadTarget(MusicianFeedItemType type, Object payload) {
        return switch (type) {
            case TRACK -> ((MusicianFeedPayloads.Track) payload).mediaAssetId();
            case PROFILE_MEDIA -> ((MusicianFeedPayloads.ProfileMedia) payload).mediaAssetId();
            case COLLAB -> ((MusicianFeedPayloads.Collab) payload).listing().id();
            case EVENT -> ((MusicianFeedPayloads.Event) payload).event().id();
            case EVENT_PROFILE_SHARE -> ((MusicianFeedPayloads.Event) payload).publicationId();
            case OVERTHINKING_PROFILE_SHARE, TABLEGROUP_PROFILE_SHARE ->
                    ((MusicianFeedPayloads.ProfileShare) payload).shareId();
            case PROFILE -> ((MusicianFeedPayloads.Profile) payload).profileId();
            case ACTIVITY_FOLLOW, ACTIVITY_LIKE, ACTIVITY_COMMENT -> payloadTarget(
                    ((MusicianFeedPayloads.Activity) payload).targetItemType(),
                    ((MusicianFeedPayloads.Activity) payload).targetPayload());
            case PROFILE_COMPLETION, SPONSORED -> null;
        };
    }

    private static boolean safeCtaUrl(String value) {
        if (!text(value, MAX_URL) || value.startsWith("//")) return false;
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()) return value.startsWith("/") && uri.getHost() == null;
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean text(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private static boolean optionalText(String value, int maxLength) {
        return value == null || (!value.isBlank() && value.length() <= maxLength);
    }

    private static IllegalArgumentException invalid(String providerId) {
        return new IllegalArgumentException("Invalid musician-feed candidate batch from provider "
                + (providerId == null ? "<unknown>" : providerId));
    }
}
