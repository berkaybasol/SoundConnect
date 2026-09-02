package com.berkayb.soundconnect.modules.notification.config;

import com.berkayb.soundconnect.modules.collab.outbox.CollabNotificationOutboxProperties;
import com.berkayb.soundconnect.modules.tablegroup.notification.outbox.TableGroupNotificationOutboxProperties;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationPublisherProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationOutboxLeaseConfigurationValidatorTest {

    @Test
    void acceptsBothLeasesAtTheSharedTimeoutPlusSafetyMargin() {
        NotificationPublisherProperties publisher = publisher(Duration.ofSeconds(9));
        CollabNotificationOutboxProperties collab = new CollabNotificationOutboxProperties();
        TableGroupNotificationOutboxProperties tableGroup = new TableGroupNotificationOutboxProperties();
        collab.setLeaseDuration(Duration.ofSeconds(10));
        tableGroup.setLeaseDuration(Duration.ofSeconds(10));

        try (AnnotationConfigApplicationContext context = context(publisher, collab, tableGroup)) {
            assertThatCode(context::refresh).doesNotThrowAnyException();
        }
    }

    @Test
    void startupRejectsACollabLeaseThatCanExpireWhileWaitingForConfirm() {
        NotificationPublisherProperties publisher = publisher(Duration.ofSeconds(9));
        CollabNotificationOutboxProperties collab = new CollabNotificationOutboxProperties();
        collab.setLeaseDuration(Duration.ofSeconds(9));

        try (AnnotationConfigApplicationContext context = context(
                publisher,
                collab,
                new TableGroupNotificationOutboxProperties()
        )) {
            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("app.notification.collab-outbox.lease-duration must be at least "
                            + "app.messaging.notification.publisher-confirm-timeout + 1s");
        }
    }

    @Test
    void startupRejectsATableGroupLeaseThatCanExpireWhileWaitingForConfirm() {
        NotificationPublisherProperties publisher = publisher(Duration.ofSeconds(9));
        TableGroupNotificationOutboxProperties tableGroup = new TableGroupNotificationOutboxProperties();
        tableGroup.setLeaseDuration(Duration.ofSeconds(9));

        try (AnnotationConfigApplicationContext context = context(
                publisher,
                new CollabNotificationOutboxProperties(),
                tableGroup
        )) {
            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("app.notification.table-group-outbox.lease-duration must be at least "
                            + "app.messaging.notification.publisher-confirm-timeout + 1s");
        }
    }

    private static NotificationPublisherProperties publisher(Duration timeout) {
        NotificationPublisherProperties properties = new NotificationPublisherProperties();
        properties.setPublisherConfirmTimeout(timeout);
        return properties;
    }

    private static AnnotationConfigApplicationContext context(
            NotificationPublisherProperties publisher,
            CollabNotificationOutboxProperties collab,
            TableGroupNotificationOutboxProperties tableGroup
    ) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(NotificationPublisherProperties.class, () -> publisher);
        context.registerBean(CollabNotificationOutboxProperties.class, () -> collab);
        context.registerBean(TableGroupNotificationOutboxProperties.class, () -> tableGroup);
        context.register(NotificationOutboxLeaseConfigurationValidator.class);
        return context;
    }
}
