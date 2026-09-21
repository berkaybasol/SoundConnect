package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Persistent ledger+plan locks provide cross-node safety; keyset rotation prevents one failing plan starving others. */
@Component @RequiredArgsConstructor @Slf4j
public class EventPlanScheduler {
    private final EventPlanRepository plans;
    private final EventPlanService service;
    private final EventScheduleClock clock;
    private UUID after;

    @Scheduled(fixedDelayString="${app.event-plans.poll-delay-ms:60000}",initialDelayString="${app.event-plans.initial-delay-ms:60000}")
    public synchronized void generateDue() {
        var through=clock.localNow().toLocalDate().plusDays(EventPlanRules.HORIZON_DAYS-1);
        var ids=plans.findGenerationCandidatesAfter(through,after,PageRequest.of(0,100));
        if(ids.isEmpty()){after=null;return;}
        for(UUID id:ids) {
            try {service.generate(id);}
            catch(RuntimeException failure){log.warn("Event plan generation deferred. planId={}, exceptionType={}",id,failure.getClass().getName());}
            finally {after=id;}
        }
    }
}
