package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

@Configuration
@EnableMethodSecurity // @PreAuthorize gibi anotasyonların çalışmasını sağlar
@EnableWebSecurity // Spring Security yapılandırmasını etkinleştirir
@RequiredArgsConstructor
public class SecurityConfig {
	
	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	
	/**
	 * Şifreleri BCrypt algoritması ile şifrelemek için PasswordEncoder bean'i tanımlıyoruz.
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
	
	/**
	 * AuthenticationManager, login işlemlerinde kullanıcıyı doğrulamak için kullanılır.
	 * AuthenticationConfiguration üzerinden elde edilir.
	 */
	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}
	
	/**
	 * Uygulamadaki güvenlik zincirini tanımlar:
	 * - CSRF kapatılır
	 * - Stateless session yapısı kurulur (JWT için)
	 * - CORS izinleri tanımlanır
	 * - Hangi endpointler serbest, hangileri korumalı ayarlanır
	 * - JWT filtresi security zincirine eklenir
	 */
	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(
								"/api/v1/auth/**",           // Auth işlemleri
								"/v3/api-docs/**",            // Swagger dökümantasyonu
								"/swagger-ui/**",             // Swagger UI
								"/swagger-ui.html",           // Swagger ana giriş
								"/swagger-resources/**",
								"/webjars/**"
						).permitAll()
						.anyRequest().authenticated()     // Diğer tüm istekler yetkilendirme ister
				)
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
		
		return http.build();
	}
	
	/**
	 * CORS yapılandırmasını tanımlar:
	 * - Frontend'den gelen isteklere izin verir
	 * - İzin verilen origin: http://localhost:3000
	 * - Tüm HTTP methodlarına izin verilir
	 * - Header kısıtlaması yapılmaz
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(List.of("http://localhost:3000"));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);
		
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}
}