package com.berkayb.soundconnect.shared.messaging.events.notification;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NotificationProducerConfigurationTest {

    private static final String SHARED_ENV = "SOUNDCONNECT_NOTIFICATION_PUBLISHER_CONFIRM_TIMEOUT";
    private static final String LEGACY_ENV = "SOUNDCONNECT_COLLAB_NOTIFICATION_PUBLISHER_CONFIRM_TIMEOUT";

    @Test
    void sharedEnvironmentVariableConfiguresTheCentralProducer() throws IOException {
        assertThat(timeoutWith(SHARED_ENV + "=7s"))
                .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void legacyModuleSpecificEnvironmentVariableIsIgnored() throws IOException {
        assertThat(timeoutWith(LEGACY_ENV + "=9s"))
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void timeoutLivesUnderMessagingRatherThanTheCollabOutbox() throws IOException {
        try (AnnotationConfigApplicationContext context = contextWith()) {
            assertThat(context.getEnvironment().getProperty(
                    "app.messaging.notification.publisher-confirm-timeout"
            )).isEqualTo("5s");
            assertThat(context.getEnvironment().getProperty(
                    "app.notification.collab-outbox.publisher-confirm-timeout"
            )).isNull();
        }
    }

    private Duration timeoutWith(String... properties) throws IOException {
        try (AnnotationConfigApplicationContext context = contextWith(properties)) {
            context.getBean(NotificationProducer.class);
            return context.getBean(NotificationPublisherProperties.class).getPublisherConfirmTimeout();
        }
    }

    private AnnotationConfigApplicationContext contextWith(String... properties) throws IOException {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);

        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "application",
                new ClassPathResource("application.yml")
        );
        sources.forEach(context.getEnvironment().getPropertySources()::addLast);

        context.registerBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class));
        context.register(PublisherPropertiesConfiguration.class, NotificationProducer.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationPublisherProperties.class)
    static class PublisherPropertiesConfiguration {
    }
}
