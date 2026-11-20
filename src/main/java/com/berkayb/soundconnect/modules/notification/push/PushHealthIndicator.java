package com.berkayb.soundconnect.modules.notification.push;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component("pushDeliveryHealth")
@RequiredArgsConstructor
public class PushHealthIndicator implements HealthIndicator {
    private final ObjectProvider<PushOperations> operations;
    @Override public Health health() {
        var service=operations.getIfAvailable();
        if(service==null) return Health.up().withDetail("state","DISABLED").build();
        try {
            var summary=service.summary();
            // A successful GROUP BY omits zero-count states. Normalize only this
            // health view; absent/failed measurements must still remain unknown.
            var counts=new HashMap<>(Map.copyOf(summary.counts()));
            for (String state:List.of("PENDING","IN_FLIGHT","DEAD_LETTER","SUPPRESSED","ACCEPTED"))
                counts.putIfAbsent(state,0L);
            return Health.status(summary.degraded()?"DEGRADED":"UP").withDetail("counts",Map.copyOf(counts))
                    .withDetail("oldestPendingAt",summary.oldestPendingAt()==null?"none":summary.oldestPendingAt()).build();
        } catch(Exception failure) { return Health.unknown().withDetail("reason","PUSH_STORE_UNAVAILABLE").build(); }
    }
}
