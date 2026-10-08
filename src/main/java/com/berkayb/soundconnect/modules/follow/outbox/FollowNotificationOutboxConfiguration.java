package com.berkayb.soundconnect.modules.follow.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FollowNotificationOutboxProperties.class)
public class FollowNotificationOutboxConfiguration {

    public static final String EXECUTOR_BEAN = "followNotificationOutboxExecutor";

    @Bean(name = EXECUTOR_BEAN)
    public Executor followNotificationOutboxExecutor(FollowNotificationOutboxProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerThreads());
        executor.setMaxPoolSize(properties.getWorkerThreads());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("follow-notification-outbox-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
