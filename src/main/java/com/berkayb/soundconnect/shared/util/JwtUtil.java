package com.berkayb.soundconnect.shared.util;

import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class JwtUtil {
	private final JwtTokenProvider jwtTokenProvider;
	
	@Autowired
	public JwtUtil(JwtTokenProvider jwtTokenProvider) {
		this.jwtTokenProvider = jwtTokenProvider;
	}
	
	// Token'ı request'ten çek, userId'yi dön
	public UUID extractUserIdFromRequest(HttpServletRequest request) {
		String bearer = request.getHeader("Authorization");
		if (bearer != null && bearer.startsWith("Bearer ")) {
			String token = bearer.substring(7);
			return jwtTokenProvider.getUserIdFromToken(token);
		}
		throw new RuntimeException("JWT bulunamadı!");
	}
	
	// Sadece token çekilecekse:
	public String getTokenFromRequest(HttpServletRequest request) {
		String bearer = request.getHeader("Authorization");
		if (bearer != null && bearer.startsWith("Bearer ")) {
			return bearer.substring(7);
		}
		return null;
	}
}