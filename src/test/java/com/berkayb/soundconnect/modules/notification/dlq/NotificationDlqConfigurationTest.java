package com.berkayb.soundconnect.modules.notification.dlq;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class NotificationDlqConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationDlqProperties.class) static class Config { }
    final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class)).withUserConfiguration(Config.class);
    @Test void defaultsKeepRecoveryDisabledAndBoundWork() {
        runner.run(c -> { assertThat(c).hasNotFailed(); assertThat(c.getBean(NotificationDlqProperties.class).isReplayEnabled()).isFalse(); });
    }
    @Test void invalidLimitsFailStartup() {
        for (String property : new String[]{"window=26", "max-body-bytes=0", "max-window-bytes=1048577", "rpc-timeout-ms=5001", "deadline-ms=1000", "stale-ms=30000", "interval-ms=0"})
            runner.withPropertyValues("app.messaging.notification.dlq-ops." + property).run(c -> assertThat(c).hasFailed());
    }
    @Test void publicHealthHasNoMessageOrConnectionDetails() {
        var ops = org.mockito.Mockito.mock(NotificationDlqOperations.class);
        org.mockito.Mockito.when(ops.summary()).thenReturn(new NotificationDlqOperations.Summary("UNAVAILABLE", null, null, null, null, true, null, false, 10));
        var health = new NotificationDlqConfiguration().notificationDlqHealthIndicator(ops).health();
        assertThat(health.getStatus().getCode()).isEqualTo("UNKNOWN"); assertThat(health.getDetails()).isEmpty();
    }
}
