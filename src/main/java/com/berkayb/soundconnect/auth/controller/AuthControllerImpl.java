package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.service.UserService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Auth Controller", description = "register, login includes transactions")
public class AuthControllerImpl implements AuthController {
	private final AuthService authService;
	private final UserService userService;
	private final JwtTokenProvider jwtTokenProvider;
	
	
	@Override
	@PostMapping(REGISTER)
	public ResponseEntity<BaseResponse<LoginResponse>> register(@RequestBody @Valid RegisterRequestDto request, HttpServletResponse response) {
		// 1. Kullanıcıyı oluştur ve DTO dön
		BaseResponse<LoginResponse> registerResponse = authService.register(request);
		
		// 2. Token üretimi için user'ı tekrar bul
		User user = userService.findByUsername(request.username())
		                       .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		
		UserDetailsImpl userDetails = UserDetailsImpl.build(user);
		String token = jwtTokenProvider.generateToken(userDetails);
		
		// 3. Cookie oluştur
		Cookie cookie = new Cookie("token", token);
		cookie.setHttpOnly(true);
		cookie.setSecure(true); // Dev'de gerekirse false
		cookie.setPath("/");
		cookie.setMaxAge(24 * 60 * 60); // 1 gün
		
		// 4. Cookie'yi ekle
		response.addCookie(cookie);
		
		// 5. ResponseEntity olarak dön
		return ResponseEntity.status(201).body(registerResponse);
	}
	
	
	@Override
	@PostMapping(LOGIN)
	public ResponseEntity<BaseResponse<LoginResponse>> login(@RequestBody LoginRequestDto loginRequestDto, HttpServletResponse response) {
		// 1. Kullanıcı bilgisi dönsün
		BaseResponse<LoginResponse> loginResponse = authService.login(loginRequestDto);
		
		// 2. Token üretimi için kullanıcıyı bul
		User user = userService.findByUsername(loginRequestDto.username())
		                       .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		
		UserDetailsImpl userDetails = UserDetailsImpl.build(user);
		String token = jwtTokenProvider.generateToken(userDetails);
		
		// 3. Cookie oluştur
		Cookie cookie = new Cookie("token", token);
		cookie.setHttpOnly(true);
		cookie.setSecure(true); // Dev ortamında false yapabilirsin
		cookie.setPath("/");
		cookie.setMaxAge(24 * 60 * 60); // 1 gün
		
		// 4. Cookie'yi response'a ekle
		response.addCookie(cookie);
		
		// 5. Cevabı HTTP 200 olarak dön
		return ResponseEntity.ok(loginResponse);
	}
}