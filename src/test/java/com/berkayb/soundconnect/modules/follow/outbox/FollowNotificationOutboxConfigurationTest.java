package com.berkayb.soundconnect.modules.follow.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.assertj.core.api.Assertions.*;

class FollowNotificationOutboxConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(FollowNotificationOutboxConfiguration.class);

    @Test void actualConfigurationBootsBoundedExecutorWithValidatedDefaults() {
        context.run(c -> {
            assertThat(c).hasNotFailed();
            var workers=c.getBean(FollowNotificationOutboxConfiguration.EXECUTOR_BEAN,ThreadPoolTaskExecutor.class);
            assertThat(workers.getCorePoolSize()).isEqualTo(2);
            assertThat(workers.getMaxPoolSize()).isEqualTo(2);
            assertThat(workers.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(250);
        });
    }
    @Test void invalidWorkerAndAttemptBoundsFailBoot() {
        context.withPropertyValues("app.notification.follow-outbox.worker-threads=0",
                "app.notification.follow-outbox.max-attempts=101").run(c -> assertThat(c).hasFailed());
    }
    @Test void unsafeRetryTimingFailsBoot() {
        context.withPropertyValues("app.notification.follow-outbox.retry-initial-delay=30s",
                "app.notification.follow-outbox.retry-max-delay=1s").run(c -> assertThat(c).hasFailed());
    }
}
