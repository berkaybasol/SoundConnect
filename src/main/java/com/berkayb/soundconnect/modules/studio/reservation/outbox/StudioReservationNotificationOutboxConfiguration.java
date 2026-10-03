package com.berkayb.soundconnect.modules.studio.reservation.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StudioReservationNotificationOutboxProperties.class)
public class StudioReservationNotificationOutboxConfiguration {

    public static final String EXECUTOR_BEAN = "studioReservationNotificationOutboxExecutor";

    @Bean(name = EXECUTOR_BEAN)
    public Executor studioReservationNotificationOutboxExecutor(StudioReservationNotificationOutboxProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerThreads());
        executor.setMaxPoolSize(properties.getWorkerThreads());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("studio-reservation-notification-outbox-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
