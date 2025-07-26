package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleAuthRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.google.api.client.json.jackson2.JacksonFactory;


import java.util.List;
import java.util.Set;

/**
 * Bu sinif google ile login/register islemlerini gerceklestiren servis sinifidir.
 * Google'dan gelen tokeni dogrular
 * Kullaniciyi db'de bulur veya olusturur
 * JWT uretip response olarak doner.
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleAuthService {
	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final JwtTokenProvider jwtTokenProvider;
	
	// yml'den client id yi cekiyoruz
	@Value("${spring.security.oauth2.client.registration.google.client-id}")
	private String googleClientId;
	
	// google ile register/login istegi geldiginde calisacak method.
	public BaseResponse<LoginResponse> loginWithGoogle(GoogleAuthRequestDto dto) {
		log.info("Google ile login islemi baslatildi.");
		
		// google'in id token dogrulayicisini olusturuyoruz.
		// bu dogrulayici, google sign-in ile gelen tokenlarin gecerli olup olmadigini kontrol eder
		GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
				new NetHttpTransport(), // http isteklerini yapan google api nesnesi.
				JacksonFactory.getDefaultInstance() // json verisini java nesnesine donusturuyoruz (parse ediyoruz)
		)
				.setAudience(List.of(googleClientId)) // tokenin soundconnect icin olusturulup olusturulmadigini
				// kontrol eder
				.build();
		
		GoogleIdToken idToken;
		try {
			idToken = verifier.verify(dto.idToken());
		} catch (Exception e) {
			log.error("Google ID token dogrulanirken hata olustu: {}.", e.getMessage());
			throw new SoundConnectException(ErrorType.UNAUTHORIZED, List.of("Google ID token dogrulanamadi."));
		}
		if (idToken == null) {
			log.warn("Gecersiz Google ID Token geldi. Token baslangici: {}",
			         dto.idToken() != null && dto.idToken().length() > 8 ? dto.idToken().substring(0, 8) : "null");
			throw new SoundConnectException(ErrorType.UNAUTHORIZED, List.of("Geçersiz Google ID Token!"));
		}
		GoogleIdToken.Payload payload = idToken.getPayload();
		String email = payload.getEmail();
		boolean emailVerified = Boolean.TRUE.equals(payload.getEmailVerified());
		String name = (String) payload.get("name");
		
		log.info("Google kullanici dogrulandi: email={}", email);
		
		User user = userRepository.findByEmail(email).orElse(null);
		
		if (user == null) {
			Role defaultRole = roleRepository.findByName(RoleEnum.ROLE_USER.name())
					.orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
			
			user = User.builder()
					.username(email)
					.email(email)
					.emailVerified(emailVerified)
					.provider(AuthProvider.GOOGLE)
					.status(UserStatus.ACTIVE)
					.roles(Set.of(defaultRole))
					.build();
			userRepository.save(user);
			log.info("Yeni kullanici Google ile kayit edildi. email={}", email);
		} else {
			if (user.getProvider() != AuthProvider.GOOGLE) {
				log.error("Bu email ile local kullanici zaten kayitli. email={}", email);
				throw new SoundConnectException(ErrorType.UNAUTHORIZED, List.of("Bu email le google login yapilamaz."));
			}
		}
		UserDetailsImpl userDetails = UserDetailsImpl.fromUser(user);
		String token = jwtTokenProvider.generateToken(userDetails);
		log.info("Google ile login islemi basarili. email={}", email);
		
		return BaseResponse.<LoginResponse>builder()
				.success(true)
				.message("Google ile giris basarili")
				.code(200)
				.data(new LoginResponse(token))
				.build();
	}
	
	
}