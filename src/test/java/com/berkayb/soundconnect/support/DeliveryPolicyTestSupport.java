package com.berkayb.soundconnect.support;

import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import static org.mockito.Mockito.*;

/** Existing isolated domain tests exercise their own boundary; erasure has real PostgreSQL tests. */
public final class DeliveryPolicyTestSupport {
    public static NotificationDeliveryPolicy immediateAllowedPolicy() {
        var policy=mock(NotificationDeliveryPolicy.class);
        lenient().when(policy.eligible(any())).thenReturn(true);
        lenient().doAnswer(call -> { ((Runnable)call.getArgument(2)).run(); return null; })
                .when(policy).schedule(any(),any(),any());
        return policy;
    }
    public static AccountDeliveryFence activeAccounts() {
        var accounts=mock(AccountDeliveryFence.class);
        lenient().when(accounts.canDeliver(any(),any())).thenReturn(true);
        return accounts;
    }
    @TestConfiguration(proxyBeanMethods=false)
    public static class Config {
        @Bean NotificationDeliveryPolicy deliveryPolicy() { return immediateAllowedPolicy(); }
    }
}
