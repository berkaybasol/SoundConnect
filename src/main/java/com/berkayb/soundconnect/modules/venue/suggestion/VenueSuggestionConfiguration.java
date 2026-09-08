package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VenueSuggestionProperties.class)
public class VenueSuggestionConfiguration {
    @Bean @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<VenueSuggestionSubmissionFilter> venueSuggestionSubmissionFilter(
            VenueSuggestionRateGuard guard, SecurityErrorResponseWriter errors) {
        var registration = new FilterRegistrationBean<>(new VenueSuggestionSubmissionFilter(guard, errors));
        registration.setOrder(-90); // Security's default order is -100, including its CORS processing.
        return registration;
    }
    @Bean("venueSuggestionDispatchExecutor")
    Executor venueSuggestionDispatchExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("venue-suggestion-dispatch-");
        executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
