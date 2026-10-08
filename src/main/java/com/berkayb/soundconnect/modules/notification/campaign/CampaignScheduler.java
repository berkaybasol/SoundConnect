package com.berkayb.soundconnect.modules.notification.campaign;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.notification.campaigns.enabled", havingValue = "true")
public class CampaignScheduler implements ApplicationRunner {
    private final CampaignStore store;
    private final CampaignWorker worker;
    private UUID after;
    @Override
    public void run(ApplicationArguments args) {
        if (!Boolean.TRUE.equals(store.jdbc().queryForObject(
                "select exists(select 1 from soundconnect_schema_migrations where migration_id='2026-10-07-notification-campaigns')",
                Map.of(), Boolean.class))) {
            throw new IllegalStateException("Apply notification-campaigns migration before enabling scheduler");
        }
    }

    @Scheduled(
            fixedDelayString = "${app.notification.campaigns.poll-delay-ms:1000}",
            initialDelayString = "${app.notification.campaigns.initial-delay-ms:30000}")
    public synchronized void tick() {
        var args = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("after", after)
                .addValue("now", Timestamp.from(Instant.now()));
        var ids = store.jdbc().queryForList(
                "select id from tbl_notification_campaign where status='SCHEDULED' and next_run_at<=:now and (cast(:after as uuid) is null or id>cast(:after as uuid)) order by id limit 10",
                args, UUID.class);
        if (ids.isEmpty()) {
            after = null;
            return;
        }
        for (UUID id : ids) {
            try {
                worker.process(id, Instant.now());
            } catch (RuntimeException failure) {
                log.warn("Campaign batch deferred. campaignId={}, exceptionType={}",
                        id, failure.getClass().getSimpleName());
            } finally {
                after = id;
            }
        }
    }
}
