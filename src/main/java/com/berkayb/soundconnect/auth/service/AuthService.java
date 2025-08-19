package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.dto.response.RegisterResponseDto;
import com.berkayb.soundconnect.auth.otp.dto.request.ResendCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.response.ResendCodeResponseDto;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
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
import com.berkayb.soundconnect.shared.util.EmailUtils;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

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
	private final OtpService otpService;
	private final EmailUtils emailUtils;
	
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
		if (!Boolean.TRUE.equals(user.getEmailVerified())) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED, List.of("E-posta adresiniz henüz doğrulanmamış. Lütfen gelen kutunuzu kontrol edin."));
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
	
	
	@Transactional
	public BaseResponse<RegisterResponseDto> register(RegisterRequestDto dto) {
		// normalize maili ekle
		final String normalizedEmail = emailUtils.normalize(dto.email());
		
		// kullanici adi daha once alinmis mi bak alinmissa hata firlat.
		if (userRepository.existsByUsername(dto.username())){
			throw new SoundConnectException(ErrorType.USER_ALREADY_EXISTS);
		}
		
		// mail daha once alinmis mi bak alinsmissa hata firlat
		if (userRepository.existsByEmail(normalizedEmail)) {
			throw new SoundConnectException(ErrorType.EMAIL_ALREADY_EXISTS);
		}
		
		// sadece izin verilen rollerle kayit olunabilir. yoksa hata firlat.
		if (!REGISTER_ALLOWED_ROLES.contains(dto.role())) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED,
			                                List.of("bu rol ile kayıt olunamaz. sistem yöneticisiyle görüş."));
		}
		
		// secilen rolu kaydet.
		RoleEnum selectedRoleEnum = dto.role();
		
		// şifreyi hashle
		String encodedPassword = passwordEncoder.encode(dto.password());
		
		// mail kuyruguna sorunsuz gitti mi? default false
		boolean mailQueued = false;
		
		// eğer başvuru mekan sahibi (ROLE_VENUE) ise
		if (selectedRoleEnum == RoleEnum.ROLE_VENUE) {
			// dinleyici rolünü ver
			Role listenerRole = roleRepository.findByName(RoleEnum.ROLE_LISTENER.name())
			                                  .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND,
			                                                                               List.of("ROLE_LISTENER " +
					                                                                                       "bulunamadı!")));
			
			// user'ı dinleyici olarak kaydet, status pending_venue_request olsun
			User user = User.builder()
			                .username(dto.username())
			                .email(normalizedEmail)
			                .roles(Set.of(listenerRole))
			                .password(encodedPassword)
			                .status(UserStatus.PENDING_VENUE_REQUEST)
			                .emailVerified(false)
			                .build();
			
			// kullanıcıyı kaydet
			userRepository.save(user);
			
			// TODO: buraya admin'e başvuru bildirimi veya özel bir mail logic'i ekle
			
			
			// OTP kodu uret ve mail ile gonder (RabbitMQ uzerinden async)
			String otpCode = otpService.generateAndCacheOtp(user.getEmail());
			try {
				mailProducer.sendVerificationMail(user.getEmail(), otpCode);
				mailQueued = true;
			} catch (Exception e) {
				log.error("mail queue error for email={} code={}", user.getEmail(), otpCode, e);
			}
			
			long ttl = otpService.getOtpTimeLeftSeconds(user.getEmail());
			
			return BaseResponse.<RegisterResponseDto>builder()
					.success(true)
					.message("Basvurun alindi. biz sizinle iletisime gecene kadar gecici olarak dinleyici olarak " +
							         "kaydedildin. size en kisa sure icerisinde geri donus yapacagiz! Mail adresinden" +
							         " kaydini onaylamayi unutma!")
					.code(201)
					.data(new RegisterResponseDto(user.getEmail(), UserStatus.PENDING_VENUE_REQUEST, ttl, mailQueued))
					.build();
		}
		
		// burdan sonrasi tum roller..
		// ilgili rolu bul
		Role selectedRole = roleRepository.findByName(selectedRoleEnum.name())
		                                  .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND,
		                                                                               List.of("geçersiz rol seçimi veya sistemde tanımlı değil.")));
		
		
		// yeni kullanıcıyı oluştur
		User user = User.builder()
		                .username(dto.username())
		                .email(normalizedEmail)
		                .roles(Set.of(selectedRole))
		                .password(encodedPassword)
		                .status(UserStatus.INACTIVE)
		                .emailVerified(false)
		                .build();
		
		// kullanıcıyı kaydet
		userRepository.save(user);
		
		// kullanıcının rolüne göre otomatik profil oluştur, sadece venue'da açılmaz
		if (selectedRoleEnum != RoleEnum.ROLE_VENUE) {
			profileFactory.createProfileIfNeeded(user, selectedRoleEnum);
		}
		
		// OTP kodu uret ve mail ile gonder (RABBITMQ uzerinden async)
		String otpCode = otpService.generateAndCacheOtp(user.getEmail());
		try {
			mailProducer.sendVerificationMail(user.getEmail(), otpCode);
			mailQueued = true;
		} catch (Exception e) {
			log.error("mail queue error for email={} code={}", user.getEmail(), otpCode, e);
		}
		
		long ttl = otpService.getOtpTimeLeftSeconds(user.getEmail());
		
		// succes response
		return BaseResponse.<RegisterResponseDto>builder()
		                   .success(true)
		                   .message("kayıt alındı. lütfen e-posta adresinden kaydını doğrula.")
		                   .code(201)
		                   .data(new RegisterResponseDto(user.getEmail(), UserStatus.INACTIVE, ttl, mailQueued))
		                   .build();
	}
	
	public BaseResponse<Void> verifyCode(VerifyCodeRequestDto dto){
		// kullaniciyi email ile bul
		final String email = emailUtils.normalize(dto.email());
		User user = userRepository.findByEmail(email)
		                          .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		
		// kullanici zaten dogrulanmissa response don
		if (Boolean.TRUE.equals(user.getEmailVerified())) {
			return BaseResponse.<Void>builder()
					.success(true)
					.code(200)
					.message("zaten dogrulanmis")
					.data(null)
					.build();
		}
		// otp kodunu kontrol et (sure, brute-force vs.)
		boolean valid = otpService.verifyOtp(email, dto.code());
		if (!valid) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		
		// kullanici dogrula
		user.setEmailVerified(true);
		
		// kullanici venue degilse status'u aktif yap ve kaydet
		if (user.getStatus() != UserStatus.PENDING_VENUE_REQUEST) {
			user.setStatus(UserStatus.ACTIVE);
		}
		userRepository.save(user);
		
		
		// succes reponse
		return BaseResponse.<Void>builder()
				.success(true)
				.code(200)
				.message("mail basariyla dogrulandi")
				.build();
	}
	
	@Transactional
	public BaseResponse<ResendCodeResponseDto> resendCode (ResendCodeRequestDto dto) {
		final String email = emailUtils.normalize(dto.email());
		
		User user = userRepository.findByEmail(email)
				.orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		
		// zaten dogrulanmis ise idempotent (kac kere denerse denesin ayi sonuc) 200 don.
		if (Boolean.TRUE.equals(user.getEmailVerified())) {
			return BaseResponse.<ResendCodeResponseDto>builder()
					.success(true)
					.code(200)
					.message("hesabin zaten dogrulanmis. yeni kod gondermedik")
					.data(new ResendCodeResponseDto(0, false, 0))
					.build();
		}
		
		// cooldown kontrolu
		long cooldownLeft = otpService.getResendCooldownLeftSeconds(email);
		if (cooldownLeft > 0) {
			long currentOtpTtl = otpService.getOtpTimeLeftSeconds(email);
			return BaseResponse.<ResendCodeResponseDto>builder()
					.success(false)
					.code(429) // Too Many Requests semantigi (HTTP 200 donecek olsa da kod alani 429)
					.message("cok sik istek: lutfen biraz bekleyip tekrar deneyin..")
					.data(new ResendCodeResponseDto(currentOtpTtl, false, cooldownLeft))
					.build();
		}
		
		// yeni OTP uret ve mail at
		String otpCode = otpService.generateAndCacheOtp(email);
		boolean mailQueued = false;
		
		try {
			mailProducer.sendVerificationMail(email, otpCode);
			mailQueued = true;
		} catch (Exception e) {
			log.error("mail queue error (resend) for email={} code={}", email, otpCode, e);
		}
		
		// cooldown'i baslat
		otpService.startResendCooldown(email);
		
		long ttl = otpService.getOtpTimeLeftSeconds(email);
		return BaseResponse.<ResendCodeResponseDto>builder()
				.success(true)
				.code(200)
				.message("yeni dogrulama kodu e-postana gonderildi")
				.data(new ResendCodeResponseDto(ttl, mailQueued, otpService.getResendCooldownLeftSeconds(email)))
				.build();
	}
	
}