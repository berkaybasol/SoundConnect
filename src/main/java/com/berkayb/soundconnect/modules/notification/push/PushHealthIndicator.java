package com.berkayb.soundconnect.modules.notification.push;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component("pushDeliveryHealth")
@RequiredArgsConstructor
public class PushHealthIndicator implements HealthIndicator {
    private final ObjectProvider<PushOperations> operations;
    @Override public Health health() {
        var service=operations.getIfAvailable();
        if(service==null) return Health.up().withDetail("state","DISABLED").build();
        try {
            var summary=service.summary();
            return Health.status(summary.degraded()?"DEGRADED":"UP").withDetail("counts",summary.counts())
                    .withDetail("oldestPendingAt",summary.oldestPendingAt()==null?"none":summary.oldestPendingAt()).build();
        } catch(Exception failure) { return Health.unknown().withDetail("reason","PUSH_STORE_UNAVAILABLE").build(); }
    }
}
