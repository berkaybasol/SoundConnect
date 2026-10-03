package com.berkayb.soundconnect.shared.config;

import org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Explicitly imported only by fixtures whose broker connections are test doubles.
 * Keep listener beans and their handlers, but never start asynchronous consumers
 * against those doubles. Broker integration and media-worker tests do not import this.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ExternalRabbitConsumersTestIsolation {
    @Bean
    static BeanPostProcessor disableExternalConsumers() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String name) {
                // The legacy mail factory does not consume Boot's global auto-startup property.
                if (bean instanceof AbstractRabbitListenerContainerFactory<?> factory) {
                    factory.setAutoStartup(false);
                }
                return bean;
            }
        };
    }
}
