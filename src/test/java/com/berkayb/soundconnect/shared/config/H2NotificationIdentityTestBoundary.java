package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.modules.notification.config.BandNotificationIdentitySchema;
import com.berkayb.soundconnect.modules.notification.config.MediaNotificationIdentitySchema;
import com.berkayb.soundconnect.modules.notification.dlq.NotificationDlqBroker;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Explicit boundary for H2 controller/service tests that do not exercise PostgreSQL schema startup.
 * PostgreSQL checks remain in MediaNotificationIdentityMigrationPostgresTest and real
 * full-application fixtures such as MarketplaceHttpSecurityPostgresTest. Do not apply this
 * annotation to those fixtures.
 * No security filter, domain service or repository is replaced by this annotation.
 * Asynchronous Rabbit consumers stay stopped against these fixtures' broker doubles.
 * The dedicated DLQ transport is also an external I/O double; real broker tests
 * exercise it separately and must never use this annotation.
 */
@Target(TYPE)
@Retention(RUNTIME)
@Documented
@Inherited
@Import(ExternalRabbitConsumersTestIsolation.class)
@MockitoBean(types={MediaNotificationIdentitySchema.class,BandNotificationIdentitySchema.class,NotificationDlqBroker.class},enforceOverride=true)
public @interface H2NotificationIdentityTestBoundary {
}
