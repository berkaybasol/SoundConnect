package com.berkayb.soundconnect.modules.notification.push;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushProperties.class)
public class PushConfiguration {
    @Bean("pushClock") Clock pushClock() { return Clock.systemUTC(); }

    @Bean("pushDeliveryExecutor")
    @ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
    ThreadPoolTaskExecutor pushDeliveryExecutor(PushProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerThreads());
        executor.setMaxPoolSize(properties.getWorkerThreads());
        executor.setQueueCapacity(0); // Database is the queue; never lease work waiting in memory.
        executor.setThreadNamePrefix("push-delivery-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
