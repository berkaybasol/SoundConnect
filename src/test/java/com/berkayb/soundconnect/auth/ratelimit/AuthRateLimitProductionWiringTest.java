package com.berkayb.soundconnect.auth.ratelimit;

import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuthRateLimitProductionWiringTest {

	@Test
	void completeApplicationContextWiresLimiterIntoTheHttpFilter() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
			context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
			context.register(
					AuthRateLimitConfiguration.class,
					AuthRateLimiter.class,
					SecurityErrorResponseWriter.class
			);
			context.refresh();

			AuthRateLimiter limiter = context.getBean(AuthRateLimiter.class);
			AuthRateLimitFilter filter = context.getBean(AuthRateLimitFilter.class);
			FilterRegistrationBean<?> servletRegistration = context.getBean(
					"authRateLimitServletRegistration",
					FilterRegistrationBean.class
			);
			assertThat(ReflectionTestUtils.getField(filter, "rateLimiter")).isSameAs(limiter);
			assertThat(servletRegistration.isEnabled()).isFalse();
		}
	}
}
