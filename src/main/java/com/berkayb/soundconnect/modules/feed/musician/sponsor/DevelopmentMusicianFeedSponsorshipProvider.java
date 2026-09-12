package com.berkayb.soundconnect.modules.feed.musician.sponsor;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliverySnapshot;
import com.berkayb.soundconnect.modules.feed.musician.provider.MusicianFeedCollabCandidateProvider;
import com.berkayb.soundconnect.modules.feed.musician.provider.MusicianFeedEventCandidateProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit local fixture only; production startup rejects this flag. */
@Component
@ConditionalOnProperty(prefix = "app.feed.musician", name = "mock-sponsors-enabled", havingValue = "true")
public class DevelopmentMusicianFeedSponsorshipProvider implements MusicianFeedSponsorshipProvider {
    private static final Logger log = LoggerFactory.getLogger(DevelopmentMusicianFeedSponsorshipProvider.class);
    private static final int NATIVE_QUERY_LIMIT = 6;
    private static final int PLACEMENTS_PER_NATIVE_TYPE = 2;
    private static final List<Creative> CREATIVES = List.of(
            new Creative("find-your-band", "Bir sonraki grubun burada olabilir",
                    "Sesine eşlik edecek müzisyenlerle tanış; yeni projelerin ilk adımını birlikte atın.",
                    "Collab'ı keşfet", "/collab"),
            new Creative("live-music-this-week", "Bu hafta canlı müziğe yer aç",
                    "Şehrindeki sahneleri ve yeni sesleri keşfet. Bir sonraki ilhamın sahnede seni bekliyor.",
                    "Etkinlikleri keşfet", "/event-discovery"),
            new Creative("rehearsal-partners", "Prova odasından sahneye",
                    "Birlikte üretmek istediğin ekip arkadaşlarını bul; prova planını müziğe dönüştür.",
                    "İlanlara göz at", "/collab"),
            new Creative("discover-local-stages", "Şehrinin ritmini yakala",
                    "Yeni bir mekân, farklı bir performans: canlı müzik takviminde sana göre bir gece var.",
                    "Programı keşfet", "/event-discovery"),
            new Creative("next-music-project", "Yeni bir projeye ses ver",
                    "Enstrümanına ve ilgi alanlarına uygun müzik fırsatlarıyla yeni bağlantılar kur.",
                    "Fırsatları keşfet", "/collab"),
            new Creative("hear-something-new", "Bu kez başka bir sahneyi dinle",
                    "Müzik çevreni genişlet; daha önce dinlemediğin performanslarla tanış.",
                    "Etkinliklere göz at", "/event-discovery"));

    private final MusicianFeedCollabCandidateProvider collabs;
    private final MusicianFeedEventCandidateProvider events;

    public DevelopmentMusicianFeedSponsorshipProvider(MusicianFeedCollabCandidateProvider collabs,
                                                      MusicianFeedEventCandidateProvider events) {
        this.collabs = collabs;
        this.events = events;
    }

    @Override public String providerId() { return "development-fixture"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() {
        return Set.of(MusicianFeedItemType.SPONSORED, MusicianFeedItemType.COLLAB, MusicianFeedItemType.EVENT);
    }

    @Override
    public List<MusicianFeedCandidate> findPlacements(MusicianFeedCandidateRequest request) {
        if (request.limit() <= 0 || Collections.disjoint(request.supportedTypes(), supportedTypes())) {
            return List.of();
        }
        Map<String, MusicianFeedCandidate> placements = new LinkedHashMap<>();
        promoteNative(placements, request, collabs, MusicianFeedItemType.COLLAB,
                MusicianFeedReasonCode.FEATURED, "Featured", "Collab'ı keşfet", "/collab");
        promoteNative(placements, request, events, MusicianFeedItemType.EVENT,
                MusicianFeedReasonCode.SPONSORED, "Sponsored", "Etkinlikleri keşfet", "/event-discovery");
        if (request.supportedTypes().contains(MusicianFeedItemType.SPONSORED)) {
            for (Creative creative : CREATIVES) {
                MusicianFeedCandidate candidate = standalone(request, creative);
                if (available(request, candidate)) placements.putIfAbsent(targetKey(candidate), candidate);
            }
        }
        return placements.values().stream().limit(request.limit()).toList();
    }

    private void promoteNative(Map<String, MusicianFeedCandidate> placements,
                               MusicianFeedCandidateRequest request,
                               MusicianFeedCandidateProvider source,
                               MusicianFeedItemType type,
                               MusicianFeedReasonCode reason,
                               String disclosure,
                               String ctaLabel,
                               String ctaUrl) {
        if (!request.supportedTypes().contains(type)) return;
        // Reuse native visibility, approval, ownership and expiry checks. The additional
        // bounded reads exist only while the development fixture flag is enabled.
        var nativeRequest = new MusicianFeedCandidateRequest(request.viewerUserId(),
                request.musicianProfileId(), request.feedSessionId(), request.anchor(), request.readAt(),
                Math.min(request.limit(), NATIVE_QUERY_LIMIT), Set.of(type), request.personalization(),
                request.feedback(), request.delivery());
        List<MusicianFeedCandidate> nativeCandidates;
        try {
            nativeCandidates = MusicianFeedCandidateContract.validateBatch(
                    source.providerId(), source.supportedTypes(), nativeRequest,
                    source.findCandidates(nativeRequest), nativeRequest.limit(), false);
        } catch (RuntimeException failure) {
            log.warn("Optional musician-feed development promotion source failed: type={}", type, failure);
            return;
        }
        int added = 0;
        for (MusicianFeedCandidate nativeCandidate : nativeCandidates) {
            if (nativeCandidate.ownedByViewer() || nativeCandidate.author() == null
                    || request.viewerUserId().equals(nativeCandidate.author().userId())) continue;
            var promotion = new MusicianFeedItemResponse.Promotion(
                    stableId("campaign:" + targetKey(nativeCandidate)), disclosure, ctaLabel, ctaUrl);
            // Keep the native item identity so a hide/report and session deduplication
            // apply to both the organic and promoted presentations of the same content.
            var promoted = new MusicianFeedCandidate(nativeCandidate.itemId(), nativeCandidate.type(),
                    nativeCandidate.payloadVersion(), nativeCandidate.occurredAt(),
                    new MusicianFeedItemResponse.Reason(reason, List.of(), 0),
                    nativeCandidate.author(), nativeCandidate.target(), nativeCandidate.engagement(),
                    promotion, nativeCandidate.feedbackCapabilities(), nativeCandidate.payload(),
                    fixtureScore(request, targetKey(nativeCandidate)), 0, nativeCandidate.lane(), false);
            if (available(request, promoted)
                    && placements.putIfAbsent(targetKey(promoted), promoted) == null
                    && ++added == PLACEMENTS_PER_NATIVE_TYPE) break;
        }
    }

    private MusicianFeedCandidate standalone(MusicianFeedCandidateRequest request, Creative creative) {
        UUID creativeId = stableId("creative:" + creative.key());
        var payload = new MusicianFeedPayloads.SponsoredStandalone(
                creative.title(), creative.body(), null, creative.ctaLabel(), creative.ctaUrl());
        var promotion = new MusicianFeedItemResponse.Promotion(
                stableId("campaign:standalone:" + creative.key()), "Sponsored",
                creative.ctaLabel(), creative.ctaUrl());
        return new MusicianFeedCandidate("SPONSORED:" + creativeId,
                MusicianFeedItemType.SPONSORED, 1, request.anchor(),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.SPONSORED, List.of(), 0),
                null, new MusicianFeedItemResponse.Target("STANDALONE", creativeId),
                null, promotion, List.of(MusicianFeedFeedbackAction.HIDE, MusicianFeedFeedbackAction.REPORT),
                payload, fixtureScore(request, "STANDALONE:" + creativeId), 0, MusicianFeedLane.SYSTEM, false);
    }

    private static boolean available(MusicianFeedCandidateRequest request, MusicianFeedCandidate candidate) {
        return !request.feedback().hiddenItemIds().contains(candidate.itemId())
                && !request.delivery().itemIds().contains(candidate.itemId())
                && !request.delivery().targetKeys().contains(targetKey(candidate));
    }

    private static String targetKey(MusicianFeedCandidate candidate) {
        return MusicianFeedDeliverySnapshot.targetKey(candidate.target().type(), candidate.target().id());
    }

    private static UUID stableId(String key) {
        return UUID.nameUUIDFromBytes(("musician-feed-development:" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static long fixtureScore(MusicianFeedCandidateRequest request, String targetKey) {
        // Ordering may rotate on refresh, but never creates a new campaign or creative.
        UUID order = stableId("order:" + request.feedSessionId() + ":" + targetKey);
        return 450_000L + Math.floorMod(order.getMostSignificantBits(), 400_000L);
    }

    private record Creative(String key, String title, String body, String ctaLabel, String ctaUrl) { }
}
