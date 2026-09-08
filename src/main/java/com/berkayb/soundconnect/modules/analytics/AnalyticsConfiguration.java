package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfiguration {
    @Bean @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<AnalyticsSubmissionFilter> analyticsSubmissionFilter(AnalyticsRateGuard guard, SecurityErrorResponseWriter errors) {
        var bean = new FilterRegistrationBean<>(new AnalyticsSubmissionFilter(guard, errors));
        bean.setOrder(-89); // After Spring Security/CORS, before JSON allocation and validation.
        return bean;
    }
}
