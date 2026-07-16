package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;


/**
 * Her HTTP isteğinde (request) çalışır ve gelen isteğin JWT token’ı taşıyıp taşımadığını,
 * token’ın geçerli olup olmadığını ve kullanıcıyı sisteme kimlikli şekilde tanıtıp tanıtmadığını kontrol eder.
 * Spring Security zincirinde, gerçek authentication noktası burasıdır.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
	
	private final JwtTokenProvider jwtTokenProvider;
	private final CustomUserDetailsService userDetailsService;
	private final JwtUtil jwtUtil;
	
	// OncePerRequestFilter: Her HTTP isteginde yalnizca bir kez calisan filtre temel sinifidir.
	// doFilterInfernal metodu, filtre mantigini uyguladigimiz ana methoddur.
	
	// request ->> gelen HTTP istegi (headder, token vs.)
	// response ->> HTTP yanit nesnesi (gerekirse status kod set ederiz.)
	// filterChain ->> bir sonraki filtre veya controller'a gecmek icin kullanilir.
	
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		
		// Header null mu, "Bearer " ile mi basliyor onu kontrol ediyoruz ve headerdan tokeni kesip aliyoruz.
		// bunu metodlastirdim cunku baska yerlerde de lazim oluyor
		String token = jwtUtil.getTokenFromRequest(request);
		if (token == null) {
			filterChain.doFilter(request, response);
			return;
		}
		
		final UUID userId;
		try {
			if (!jwtTokenProvider.validateToken(token)) {
				SecurityContextHolder.clearContext();
				filterChain.doFilter(request, response);
				return;
			}
			userId = jwtTokenProvider.getUserIdFromToken(token);
		} catch (JwtException | IllegalArgumentException exception) {
			// Token parsing/claim failures are authentication failures. Database and
			// infrastructure exceptions are intentionally not caught here so the
			// server can surface them as 5xx instead of a false 401.
			rejectAuthentication(request, exception);
			filterChain.doFilter(request, response);
			return;
		}

		if (SecurityContextHolder.getContext().getAuthentication() == null) {
			UserDetails userDetails;
			try {
				userDetails = userDetailsService.loadUserById(userId);
			} catch (UsernameNotFoundException exception) {
				rejectAuthentication(request, exception);
				filterChain.doFilter(request, response);
				return;
			} catch (SoundConnectException exception) {
				if (exception.getErrorType() != ErrorType.USER_NOT_FOUND) {
					throw exception;
				}
				rejectAuthentication(request, exception);
				filterChain.doFilter(request, response);
				return;
			}
			if (canAuthenticate(userDetails, request)) {
				setAuthentication(request, userDetails);
			} else {
				SecurityContextHolder.clearContext();
			}
		}
		
		// filtre -> controller -> service vs zincir devam etsin
		filterChain.doFilter(request, response);
		
	}

	private void rejectAuthentication(HttpServletRequest request, RuntimeException exception) {
		log.debug("JWT authentication rejected. method={}, path={}, exceptionType={}",
				request.getMethod(), request.getRequestURI(), exception.getClass().getSimpleName());
		SecurityContextHolder.clearContext();
	}

	private boolean canAuthenticate(UserDetails userDetails, HttpServletRequest request) {
		if (!isAccountUsable(userDetails)) {
			return false;
		}
		if (!hasNoRoles(userDetails)) {
			return true;
		}
		return isRolelessGooglePrincipal(userDetails) && isGoogleProfileCompletionRequest(request);
	}

	private void setAuthentication(HttpServletRequest request, UserDetails userDetails) {
		UsernamePasswordAuthenticationToken authToken =
				new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
		authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
		SecurityContextHolder.getContext().setAuthentication(authToken);
	}

	private boolean isAccountUsable(UserDetails userDetails) {
		return userDetails.isEnabled()
				&& userDetails.isAccountNonLocked()
				&& userDetails.isAccountNonExpired()
				&& userDetails.isCredentialsNonExpired();
	}

	private boolean hasNoRoles(UserDetails userDetails) {
		return userDetails instanceof UserDetailsImpl principal
				&& (principal.getUser().getRoles() == null || principal.getUser().getRoles().isEmpty());
	}

	private boolean isRolelessGooglePrincipal(UserDetails userDetails) {
		return userDetails instanceof UserDetailsImpl principal
				&& principal.getUser().getProvider() == AuthProvider.GOOGLE;
	}

	private boolean isGoogleProfileCompletionRequest(HttpServletRequest request) {
		return "POST".equalsIgnoreCase(request.getMethod())
				&& "/api/v1/auth/complete-google-profile".equals(request.getRequestURI());
	}

}
