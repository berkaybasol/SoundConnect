package com.berkayb.soundconnect.modules.marketplace.media;

import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Server fallback for abandoned clients; saved draft attachments are retained. */
@Component
@ConditionalOnProperty(prefix = "marketplace.media-cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class MarketplaceMediaCleanupScheduler {
    private final MediaAssetRepository assets;
    private final MarketplaceMediaLifecycle lifecycle;

    @Scheduled(fixedDelayString = "${marketplace.media-cleanup-delay-ms:3600000}",
            initialDelayString = "${marketplace.media-cleanup-initial-delay-ms:60000}")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        var candidates = assets.findMarketplaceOrphansBefore(cutoff, PageRequest.of(0, 100));
        for (var candidate : candidates) {
            try {
                lifecycle.deleteAbandonedAsset(candidate.getListingId(), candidate.getAssetId(), cutoff);
            } catch (RuntimeException failure) {
                log.warn("[marketplace-media] orphan cleanup deferred assetId={} exceptionType={}",
                        candidate.getAssetId(), failure.getClass().getSimpleName());
            }
        }
    }
}
