package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.MailProducer;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class AuthService {
	
	private final AuthenticationManager authenticationManager;
	private final JwtTokenProvider jwtTokenProvider;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final RoleRepository roleRepository;
	private final MailProducer mailProducer;
	private final ProfileFactory profileFactory;
	
	// FIXME register icin izin verilen rolleri tuttugum method. (yeni profile olusturdukca burayi guncelle)
	private static final Set<RoleEnum> REGISTER_ALLOWED_ROLES = Set.of(
			RoleEnum.ROLE_MUSICIAN,
			RoleEnum.ROLE_USER,
			RoleEnum.ROLE_VENUE,
			RoleEnum.ROLE_LISTENER,
			RoleEnum.ROLE_STUDIO,
			RoleEnum.ROLE_ORGANIZER,
			RoleEnum.ROLE_PRODUCER
	);
	
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
		// önce kullanıcı adı daha önce alınmış mı bak, varsa hata fırlat
		if (userRepository.existsByUsername(dto.username())){
			throw new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
		}
		
		// mail daha önce alınmış mı bak, varsa hata fırlat
		if (userRepository.existsByEmail(dto.email())) {
			throw new SoundConnectException(ErrorType.EMAIL_ALREADY_EXISTS);
		}
		
		// sadece izin verilen rollerle kayıt olunabilir, yoksa hata fırlat
		if (!REGISTER_ALLOWED_ROLES.contains(dto.role())) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED,
			                                List.of("bu rol ile kayıt olunamaz. sistem yöneticisiyle görüş."));
		}
		
		// seçilen rolü kaydet
		RoleEnum selectedRoleEnum = dto.role();
		
		// şifreyi hashle
		String encodedPassword = passwordEncoder.encode(dto.password());
		
		// email doğrulama tokeni ve son kullanma tarihi oluştur
		String verificationToken = UUID.randomUUID().toString();
		LocalDateTime expiry = LocalDateTime.now().plusHours(24);
		
		// eğer başvuru mekan sahibi (ROLE_VENUE) ise
		if (selectedRoleEnum == RoleEnum.ROLE_VENUE) {
			// dinleyici rolünü ver
			Role listenerRole = roleRepository.findByName(RoleEnum.ROLE_USER.name())
			                                  .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND,
			                                                                               List.of("ROLE_USER bulunamadı!")));
			
			// user'ı dinleyici olarak kaydet, status pending_venue_request olsun
			User user = User.builder()
			                .username(dto.username())
			                .email(dto.email())
			                .roles(Set.of(listenerRole))
			                .password(encodedPassword)
			                .status(UserStatus.PENDING_VENUE_REQUEST)
			                .emailVerificationToken(verificationToken)
			                .emailVerificationExpiry(expiry)
			                .build();
			
			// kullanıcıyı kaydet
			userRepository.save(user);
			
			// todo: buraya admin'e başvuru bildirimi veya özel bir mail logic'i ekle
			
			// kullanıcıya email doğrulama yolla
			mailProducer.sendVerificationMail(user.getEmail(), verificationToken);
			
			// dinleyici olarak kaydoldu, frontend'e bilgi dön
			return BaseResponse.<LoginResponse>builder()
			                   .success(true)
			                   .message("başvurun alındı, şu an dinleyici olarak kaydın yapıldı. en kısa sürede seninle iletişime geçeceğiz. lütfen e-posta adresinden hesabını doğrula.")
			                   .code(201)
			                   .data(null)
			                   .build();
		}
		
		// buradan sonrası diğer tüm roller (müzisyen, user vs.)
		
		// ilgili rolü bul
		Role selectedRole = roleRepository.findByName(selectedRoleEnum.name())
		                                  .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND,
		                                                                               List.of("geçersiz rol seçimi veya sistemde tanımlı değil.")));
		
		// yeni kullanıcıyı oluştur
		User user = User.builder()
		                .username(dto.username())
		                .email(dto.email())
		                .roles(Set.of(selectedRole))
		                .password(encodedPassword)
		                .status(UserStatus.INACTIVE)
		                .emailVerificationToken(verificationToken)
		                .emailVerificationExpiry(expiry)
		                .build();
		
		// kullanıcıyı kaydet
		userRepository.save(user);
		
		// kullanıcının rolüne göre otomatik profil oluştur, sadece venue'da açılmaz
		if (selectedRoleEnum != RoleEnum.ROLE_VENUE) {
			profileFactory.createProfileIfNeeded(user, selectedRoleEnum);
		}
		
		// kullanıcıya email doğrulama gönder
		mailProducer.sendVerificationMail(user.getEmail(), verificationToken);
		
		// frontend'e kayıt başarılı mesajı dön
		return BaseResponse.<LoginResponse>builder()
		                   .success(true)
		                   .message("kayıt alındı. lütfen e-posta adresinden kaydını doğrula.")
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