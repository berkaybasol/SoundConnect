package com.berkayb.soundconnect.modules.notification.push.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FcmPushConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FcmPushConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void disabledByDefaultWithoutCredentialsOrProject() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PushTransport.class);
            assertThat(context).doesNotHaveBean(GoogleCredentials.class);
            assertThat(context).doesNotHaveBean("fcmSendExecutor");
        });
    }

    @Test
    void explicitDisableDoesNotLoadEvenInvalidCredentialPath() {
        runner.withPropertyValues("app.notification.push.enabled=false",
                "app.notification.push.fcm.credentials-path=missing-private-file.json").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PushTransport.class);
        });
    }

    @Test
    void rejectsInfiniteOrOverLeaseTimeoutsBeforeCredentialLoading() {
        runner.withPropertyValues("app.notification.push.enabled=true",
                "app.notification.push.fcm.project-id=soundconnect-test",
                "app.notification.push.fcm.total-timeout=0s").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("FCM timeouts must be");
        });
    }

    @Test
    void enabledMissingExternalCredentialFailsWithSafeMessage() {
        runner.withPropertyValues("app.notification.push.enabled=true",
                "app.notification.push.fcm.project-id=soundconnect-test",
                "app.notification.push.fcm.credentials-path=missing-private-file.json").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("FCM credentials unavailable");
        });
    }
}
