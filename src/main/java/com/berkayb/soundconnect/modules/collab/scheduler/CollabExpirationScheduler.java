package com.berkayb.soundconnect.modules.collab.scheduler;

import com.berkayb.soundconnect.modules.collab.service.CollabService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollabExpirationScheduler {
    private final CollabService service;

    @Scheduled(fixedDelayString = "${collab.expiration.reconcile-delay-ms:60000}",
            initialDelayString = "${collab.expiration.initial-delay-ms:60000}")
    public void reconcile() {
        int expired;
        do {
            expired = service.expireDueBatch(100);
            if (expired > 0) log.info("[CollabExpiration] expired {} due listings", expired);
        } while (expired == 100);
    }
}
