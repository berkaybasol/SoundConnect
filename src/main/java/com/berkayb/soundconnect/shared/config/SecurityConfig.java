package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.shared.security.RestAccessDeniedHandler;
import com.berkayb.soundconnect.shared.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.BASE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.COMPLETE_GOOGLE_PROFILE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.FORGOT_PASSWORD;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.GOOGLE_SIGN_IN;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.LOGIN;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.PASSWORD_RESET_ACCOUNT;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.REGISTER;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.RESEND_CODE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.RESET_PASSWORD;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.USERNAME_AVAILABILITY;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.VERIFY_CODE;

/**
 * Stateless HTTP security policy. Public routes are deliberately method-scoped;
 * every route not listed here requires a valid active-account JWT.
 */
@Configuration
@EnableMethodSecurity
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final AuthRateLimitFilter authRateLimitFilter;
	private final CorsProperties corsProperties;
	private final RestAuthenticationEntryPoint authenticationEntryPoint;
	private final RestAccessDeniedHandler accessDeniedHandler;

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler)
				)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

						.requestMatchers(HttpMethod.POST, BASE + COMPLETE_GOOGLE_PROFILE).authenticated()
						.requestMatchers(
								HttpMethod.POST,
								BASE + LOGIN,
								BASE + REGISTER,
								BASE + VERIFY_CODE,
								BASE + RESEND_CODE,
								BASE + GOOGLE_SIGN_IN,
								BASE + USERNAME_AVAILABILITY,
								BASE + PASSWORD_RESET_ACCOUNT,
								BASE + FORGOT_PASSWORD,
								BASE + RESET_PASSWORD
						).permitAll()

						.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/venues/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/venue-suggestions").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/analytics/observations").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/cities/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/districts/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/neighborhoods/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/events/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/promotions/displayable/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/profiles/*/*/media").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/spotify/search/tracks").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/spotify/tracks/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/spotify/tracks/by-ids").permitAll()
						// These endpoints live under the historical public namespace, but
						// expose authenticated social identity/navigation data. Keep these
						// rules before the legacy /public/** permit-all matcher.
						.requestMatchers(
								HttpMethod.GET,
								"/api/v1/public/listener-profiles/**",
								"/api/v1/public/profiles/**"
						).authenticated()

						.requestMatchers(
								"/v3/api-docs/**",
								"/swagger-ui/**",
								"/swagger-ui.html",
								"/swagger-resources/**",
								"/webjars/**",
								"/api/ping",
								"/api/v1/public/**",
								"/ws",
								"/ws/**"
						).permitAll()
						.anyRequest().authenticated()
				)
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(authRateLimitFilter, JwtAuthenticationFilter.class);

		return http.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOriginPatterns(corsProperties.requireSafeAllowedOriginPatterns());
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of(
				"Authorization",
				"Content-Type",
				"Accept",
				"Origin",
				"X-Requested-With"
		));
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}
}
