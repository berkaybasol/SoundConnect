package com.berkayb.soundconnect.auth.ratelimit;

import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthRateLimitProperties.class)
public class AuthRateLimitConfiguration {

	@Bean
	TrustedProxyClientAddressResolver trustedProxyClientAddressResolver(
			AuthRateLimitProperties properties
	) {
		return new TrustedProxyClientAddressResolver(
				properties.getTrustedProxyCidrs(),
				properties.getForwardedHeader()
		);
	}

	@Bean
	AuthRateLimitFilter authRateLimitFilter(
			AuthRateLimiter rateLimiter,
			AuthRateLimitProperties properties,
			SecurityErrorResponseWriter responseWriter,
			TrustedProxyClientAddressResolver clientAddressResolver
	) {
		return new AuthRateLimitFilter(rateLimiter, properties, responseWriter, clientAddressResolver);
	}

	/**
	 * The filter is positioned explicitly inside Spring Security. Disabling
	 * servlet-container auto-registration prevents double quota consumption.
	 */
	@Bean
	FilterRegistrationBean<AuthRateLimitFilter> authRateLimitServletRegistration(
			AuthRateLimitFilter filter
	) {
		FilterRegistrationBean<AuthRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}
}
