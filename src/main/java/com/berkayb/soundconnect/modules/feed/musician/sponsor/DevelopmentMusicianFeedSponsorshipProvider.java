package com.berkayb.soundconnect.modules.feed.musician.sponsor;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Explicit local fixture only; production startup rejects this flag. */
@Component
@ConditionalOnProperty(prefix = "app.feed.musician", name = "mock-sponsors-enabled", havingValue = "true")
public class DevelopmentMusicianFeedSponsorshipProvider implements MusicianFeedSponsorshipProvider {
    private static final UUID CAMPAIGN_ID = UUID.nameUUIDFromBytes(
            "musician-feed-development-campaign".getBytes(StandardCharsets.UTF_8));
    private static final UUID CREATIVE_ID = UUID.nameUUIDFromBytes(
            "musician-feed-development-creative".getBytes(StandardCharsets.UTF_8));

    @Override public String providerId() { return "development-fixture"; }
    @Override public java.util.Set<MusicianFeedItemType> supportedTypes() {
        return java.util.Set.of(MusicianFeedItemType.SPONSORED);
    }

    @Override
    public List<MusicianFeedCandidate> findPlacements(MusicianFeedCandidateRequest request) {
        if (!request.supportedTypes().contains(MusicianFeedItemType.SPONSORED)) return List.of();
        String ctaUrl = "/collab";
        var payload = new MusicianFeedPayloads.SponsoredStandalone(
                "Yeni müzik bağlantıları kur",
                "Bu kart yalnız açık geliştirme bayrağıyla gösterilen deterministik bir test yerleşimidir.",
                null, "Collab'ı aç", ctaUrl);
        var promotion = new MusicianFeedItemResponse.Promotion(
                CAMPAIGN_ID, "Sponsored", "Collab'ı aç", ctaUrl);
        return List.of(new MusicianFeedCandidate("SPONSORED:" + CREATIVE_ID,
                MusicianFeedItemType.SPONSORED, 1, request.anchor(),
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.SPONSORED, List.of(), 0),
                null, new MusicianFeedItemResponse.Target("STANDALONE", CREATIVE_ID),
                null, promotion, List.of(MusicianFeedFeedbackAction.HIDE, MusicianFeedFeedbackAction.REPORT),
                payload, 500_000L, 0, MusicianFeedLane.SYSTEM, false));
    }
}
