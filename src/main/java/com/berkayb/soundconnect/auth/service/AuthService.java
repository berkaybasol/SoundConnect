package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.MailProducer;
import com.berkayb.soundconnect.shared.mail.service.MailService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
	
	private final AuthenticationManager authenticationManager;
	private final JwtTokenProvider jwtTokenProvider;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final RoleRepository roleRepository;
	private final MailProducer mailProducer;
	
	
	public BaseResponse<LoginResponse> login(LoginRequestDto request) {
		// kullanici db'den bul
		User user = userRepository.findByUsername(request.username()).
				orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		
		// email dogrulanmis mi kontrol et
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED, List.of("E-posta adresiniz henüz doğrulanmamış." +
					                                                                " Lütfen gelen kutunuzu kontrol " +
					                                                                "edin."));
		}
		
		// Spring Security authentication ile kullaniciyi dogrula.
		Authentication authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.username(), request.password())
		);
		
		// dogrulanmis kullaniciyi al (UserDetailsImpl tipine downcast ederek)
		UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
		
		// token uret
		String token = jwtTokenProvider.generateToken(userDetails);
		
		// tokeni response sinifina sarip don
		return BaseResponse.<LoginResponse>builder()
		                   .success(true)
		                   .message("Entry Successful")
		                   .code(200)
		                   .data(new LoginResponse(token))
		                   .build();
	}
	
	public BaseResponse<LoginResponse> register(RegisterRequestDto dto) {
		// Kullanici adi dahg once alinmis mi kontrol et
		if (userRepository.existsByUsername(dto.username())){
			throw new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
		}
		
		// mail daha once alinmis mi kontrol et
		if (userRepository.existsByEmail(dto.email())) {
			throw new SoundConnectException(ErrorType.EMAIL_ALREADY_EXISTS);
		}
		
		// sifreyi encode et
		String encodedPassword = passwordEncoder.encode(dto.password());
		
		// varsayilan rolu veritabanindan cek
		Role defaultRole = roleRepository.findByName(RoleEnum.ROLE_USER.name())
		                                 .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
		
		// email dogrulama token ve expiry olustur
		String verificationToken = UUID.randomUUID().toString(); // UUID den random token olusturup stringe ceviriyoruz
		LocalDateTime expiry = LocalDateTime.now().plusHours(24); // token 24 saat icinde devedisi olacak
		
		// yeni kullaniciyi olustur
		User user = User.builder()
				.username(dto.username())
				.email(dto.email())
				.phone(dto.phone())
				.gender(dto.gender())
				.city(dto.city())
				.roles(Set.of(defaultRole))
				.password(encodedPassword)
				.status(UserStatus.INACTIVE)
				.emailVerificationToken(verificationToken)
				.emailVerificationExpiry(expiry)
				// baseentityde tanimladigimiz icin gerek yok.createdAt(LocalDateTime.now())
				.build();
		
		// veritabanina kaydet
		userRepository.save(user);
		
		// 6. Email kuyruğuna at — RabbitMQ üzerinden asenkron gönderim
		mailProducer.sendVerificationMail(user.getEmail(), verificationToken);
		
		// kullaniciya mail dogrulamamak icin response at
		return BaseResponse.<LoginResponse>builder()
		                   .success(true)
		                   .message("Kayit alindi. lutfen e-posta adresinden kaydini dogrula")
		                   .code(201)
		                   .data(null)
		                   .build();
		
	}
	
	public BaseResponse<Void> verifyEmail(String token){
		// Token'dan kullanıcıyı bul
		User user = userRepository.findByEmailVerificationToken(token)
		                          .orElseThrow(() -> new SoundConnectException(ErrorType.TOKEN_NOT_FOUND, List.of("Geçersiz veya süresi dolmuş token.")));
		
		// Token süresi geçmiş mi kontrol et
		if (user.getEmailVerificationExpiry() != null && user.getEmailVerificationExpiry().isBefore(LocalDateTime.now())) {
			throw new SoundConnectException(ErrorType.TOKEN_EXPIRED, List.of("Doğrulama tokenının süresi dolmuş."));
		}
		
		// Zaten doğrulanmış mı kontrol et
		if (Boolean.TRUE.equals(user.getEmailVerified())) {
			return BaseResponse.<Void>builder()
			                   .success(false)
			                   .message("Email zaten doğrulanmış.")
			                   .code(400)
			                   .build();
		}
		
		// Doğrula ve tokeni temizle
		user.setEmailVerified(true);
		user.setEmailVerificationToken(null);
		user.setStatus(UserStatus.ACTIVE); // Durumunu da aktif yapıyoruz
		userRepository.save(user);
		
		return BaseResponse.<Void>builder()
		                   .success(true)
		                   .message("Email başarıyla doğrulandı!")
		                   .code(200)
		                   .build();
	}
}