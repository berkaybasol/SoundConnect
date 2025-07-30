package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
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
			throws ServletException, IOException {;
		
		// Header null mu, "Bearer " ile mi basliyor onu kontrol ediyoruz ve headerdan tokeni kesip aliyoruz.
		// bunu metodlastirdim cunku baska yerlerde de lazim oluyor
		String token = jwtUtil.getTokenFromRequest(request);
		if (token == null) {
			filterChain.doFilter(request, response);
			return;
		}
		
		// Token gecerli mi? token'dan usernameyi aliyoruz
		UUID userId = jwtTokenProvider.getUserIdFromToken(token);
		
		// SecurityContext bossa (kullanici daha tanitilmamissa)
		if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
			// db'den kullaniciyi bulalim
			UserDetails userDetails = userDetailsService.loadUserById(userId);
			
			// token gecerli mi diye tekrar kontrol edelim
			if (jwtTokenProvider.validateToken(token)){
				// SecurityContext'e kimlik tanimlayalim
				UsernamePasswordAuthenticationToken authToken =
						new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
				
				// web uzerinden geldigini belirtelim
				authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
				
				// spring security sistemi artik bu kullaniciyi tanisin
				SecurityContextHolder.getContext().setAuthentication(authToken);
			}
		}
		
		// filtre -> controller -> service vs zincir devam etsin
		filterChain.doFilter(request, response);
		
	}
}