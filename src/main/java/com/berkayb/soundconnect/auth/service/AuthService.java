package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.request.UsernameAvailabilityRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.dto.response.RegisterResponseDto;
import com.berkayb.soundconnect.auth.dto.response.UsernameAvailabilityResponseDto;
import com.berkayb.soundconnect.auth.otp.dto.request.ResendCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.response.ResendCodeResponseDto;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.otp.service.OtpMailService;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.request.VenueApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.request.StudioApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserIdentityConflictMapper;
import com.berkayb.soundconnect.shared.util.EmailUtils;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
	
	// Bilinmeyen kullanicilarda da BCrypt calistirarak username timing farkini azaltir.
	// Bu hash herhangi bir gercek hesaba ait degildir ve yalniz dummy karsilastirma icindir.
	private static final String DUMMY_PASSWORD_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final JwtTokenProvider jwtTokenProvider;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final RoleRepository roleRepository;
	private final ProfileFactory profileFactory;
	private final OtpService otpService;
	private final OtpMailService otpMailService;
	private final VenueApplicationService venueApplicationService;
	private final StudioApplicationService studioApplicationService;
	private final AuthAccountRateLimitGuard accountRateLimitGuard;
	
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

	public BaseResponse<UsernameAvailabilityResponseDto> usernameAvailability(
			UsernameAvailabilityRequestDto request
	) {
		String username = UsernameUtils.normalizeAndValidate(request.username());
		accountRateLimitGuard.checkUsernameAvailability(username);
		UsernameAvailabilityResponseDto data = new UsernameAvailabilityResponseDto(
				username,
				!userRepository.existsByUsername(username)
		);
		return BaseResponse.<UsernameAvailabilityResponseDto>builder()
				.success(true)
				.message(data.available()
						? "Kullanıcı adı kullanılabilir."
						: "Bu kullanıcı adı zaten kullanılıyor.")
				.code(200)
				.data(data)
				.build();
	}
	
	public BaseResponse<LoginResponse> login(LoginRequestDto request) {
		final String normalizedUsername = UsernameUtils.normalizeAndValidate(request.username());
		accountRateLimitGuard.checkLogin(normalizedUsername);
		// Hesap durumunu ancak parola dogrulandiktan sonra acikla. Bu sira,
		// pending/unverified kullanici adlarinin yanlis parolayla enumerate
		// edilmesini engeller. Bilinmeyen kullanicida da ayni BCrypt maliyeti var.
		User user = userRepository.findByUsername(normalizedUsername).orElse(null);
		if (user == null) {
			passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
			throw new SoundConnectException(ErrorType.INVALID_CREDENTIALS);
		}
		if (!passwordEncoder.matches(request.password(), user.getPassword())) {
			throw new SoundConnectException(ErrorType.INVALID_CREDENTIALS);
		}

		// Beklemedeki mekan basvurulari, admin onayi tamamlanmadan uygulamaya giremez.
		// Bu kontrol parola dogrulamasindan sonra yapilir; boylece hesap durumu
		// yanlis parola kullanan bir istemciye sizdirilmaz.
		if (user.getStatus() == UserStatus.PENDING_VENUE_REQUEST) {
			throw new SoundConnectException(ErrorType.PENDING_VENUE_APPROVAL);
		}
		if (user.getStatus() == UserStatus.PENDING_STUDIO_REQUEST) {
			throw new SoundConnectException(ErrorType.PENDING_STUDIO_APPROVAL);
		}
		
		// email dogrulanmis mi kontrol et
		if (!Boolean.TRUE.equals(user.getEmailVerified())) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED, List.of("E-posta adresiniz henüz doğrulanmamış. Lütfen gelen kutunuzu kontrol edin."));
		}
		
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}

		UserDetailsImpl userDetails = UserDetailsImpl.fromUser(user);
		
		// token uret
		String token = jwtTokenProvider.generateToken(userDetails);
		
		// tokeni response sinifina sarip don
		return BaseResponse.<LoginResponse>builder()
		                   .success(true)
		                   .message("Entry Successful")
		                   .code(200)
		                   .data(LoginResponse.fromUser(token, user))
		                   .build();
	}
	
	@Transactional
	public BaseResponse<RegisterResponseDto> register(RegisterRequestDto dto) {
		accountRateLimitGuard.checkRegister(dto.email());
		// normalize maili ekle
		final String normalizedEmail = EmailUtils.normalize(dto.email());
		final String normalizedUsername = UsernameUtils.normalizeAndValidate(dto.username());
		
		// kullanici adi daha once alinmis mi bak alinmissa hata firlat.
		if (userRepository.existsByUsername(normalizedUsername)){
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
		log.info("Registration requested role={}", selectedRoleEnum);
		
		// şifreyi hashle
		String encodedPassword = passwordEncoder.encode(dto.password());
		
		// mail kuyruguna sorunsuz gitti mi? default false
		boolean mailQueued = false;
		
		// eğer başvuru mekan sahibi (ROLE_VENUE) ise
		if (selectedRoleEnum == RoleEnum.ROLE_VENUE) {
			
			if (dto.venueName() == null || dto.venueName().isBlank()
					|| dto.venueAddress() == null || dto.venueAddress().isBlank()
					|| dto.phone() == null || dto.phone().isBlank()
					|| dto.cityId() == null || dto.cityId().isBlank()
					|| dto.districtId() == null || dto.districtId().isBlank()
					|| dto.neighborhoodId() == null || dto.neighborhoodId().isBlank()) {
				
				throw new SoundConnectException(
						ErrorType.VALIDATION_ERROR,
						List.of("Mekan başvurusu için gerekli alanlar eksik.")
				);
			}
			
			User user = User.builder()
			                .username(normalizedUsername)
			                .email(normalizedEmail)
			                .roles(Set.of()) // henuz bos rol
			                .password(encodedPassword)
			                .status(UserStatus.PENDING_VENUE_REQUEST)
			                .emailVerified(false)
			                .build();
			
			saveIdentityAndFlush(user);
			
			var application = venueApplicationService.createApplication(
					user.getId(),
					new VenueApplicationCreateRequestDto(
							dto.venueName(),
							dto.venueAddress(),
							dto.phone(),
							dto.cityId(),
							dto.districtId(),
							dto.neighborhoodId()
					)
			);
			
			// OTP üret ve mail gönder
			OtpService.OtpIssueClaim initialOtpClaim = otpService.acquireInitialOtp(user.getEmail());
			if (initialOtpClaim.acquired()) {
				try {
					otpMailService.sendVerificationMail(user.getEmail(), initialOtpClaim.code());
					mailQueued = true;
				} catch (Exception e) {
					log.error("Verification mail could not be queued for email={}", EmailUtils.maskForLog(user.getEmail()), e);
				}
			}
			
			long ttl = otpService.getOtpTimeLeftSeconds(user.getEmail());
			
			return BaseResponse.<RegisterResponseDto>builder()
			                   .success(true)
			                   .message("Başvurun alındı. Sizinle iletisime gececegiz. o zamana kadar hesabiniz beklemede.")
			                   .code(201)
			                   .data(new RegisterResponseDto(
						   user.getEmail(),
						   UserStatus.PENDING_VENUE_REQUEST,
						   ttl,
						   mailQueued,
						   application.id()
				   ))
				                   .build();
		}

		if (selectedRoleEnum == RoleEnum.ROLE_STUDIO) {
			if (dto.studioName() == null || dto.studioName().isBlank()
					|| dto.studioAddress() == null || dto.studioAddress().isBlank()
					|| dto.studioPhone() == null || dto.studioPhone().isBlank()
					|| dto.cityId() == null || dto.cityId().isBlank()
					|| dto.districtId() == null || dto.districtId().isBlank()
					|| dto.neighborhoodId() == null || dto.neighborhoodId().isBlank()) {
				throw new SoundConnectException(
						ErrorType.VALIDATION_ERROR,
						List.of("Studyo basvurusu icin gerekli alanlar eksik.")
				);
			}

			User user = User.builder()
					.username(normalizedUsername)
					.email(normalizedEmail)
					.roles(Set.of())
					.password(encodedPassword)
					.status(UserStatus.PENDING_STUDIO_REQUEST)
					.emailVerified(false)
					.build();
			saveIdentityAndFlush(user);

			var application = studioApplicationService.createApplication(
					user.getId(),
					new StudioApplicationCreateRequestDto(
							dto.studioName(), dto.studioAddress(), dto.studioPhone(),
							dto.cityId(), dto.districtId(), dto.neighborhoodId()
					)
			);

			OtpService.OtpIssueClaim initialOtpClaim = otpService.acquireInitialOtp(user.getEmail());
			if (initialOtpClaim.acquired()) {
				try {
					otpMailService.sendVerificationMail(user.getEmail(), initialOtpClaim.code());
					mailQueued = true;
				} catch (Exception e) {
					log.error("Verification mail could not be queued for email={}", EmailUtils.maskForLog(user.getEmail()), e);
				}
			}

			return BaseResponse.<RegisterResponseDto>builder()
					.success(true)
					.message("Studyo basvurunuz alindi. Hesabiniz yonetici onayina kadar beklemede.")
					.code(201)
					.data(new RegisterResponseDto(
							user.getEmail(), UserStatus.PENDING_STUDIO_REQUEST,
							otpService.getOtpTimeLeftSeconds(user.getEmail()), mailQueued,
							application.id()
					))
					.build();
		}
		
		// burdan sonrasi tum roller..
		// ilgili rolu bul
		Role selectedRole = roleRepository.findByName(selectedRoleEnum.name())
		                                  .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND,
		                                                                               List.of("geçersiz rol seçimi veya sistemde tanımlı değil.")));
		
		// yeni kullanıcıyı oluştur
		User user = User.builder()
		                .username(normalizedUsername)
		                .email(normalizedEmail)
		                .roles(Set.of(selectedRole))
		                .password(encodedPassword)
		                .status(UserStatus.INACTIVE)
		                .emailVerified(false)
		                .build();
		
		// kullanıcıyı kaydet
		saveIdentityAndFlush(user);
		
		// kullanıcının rolüne göre otomatik profil oluştur, sadece venue'da açılmaz
		if (selectedRoleEnum != RoleEnum.ROLE_VENUE && selectedRoleEnum != RoleEnum.ROLE_STUDIO) {
			profileFactory.createProfileIfNeeded(user, selectedRoleEnum);
		}
		
		// OTP kodu uret ve mail ile gonder
		OtpService.OtpIssueClaim initialOtpClaim = otpService.acquireInitialOtp(user.getEmail());
		if (initialOtpClaim.acquired()) {
			try {
				otpMailService.sendVerificationMail(user.getEmail(), initialOtpClaim.code());
				mailQueued = true;
			} catch (Exception e) {
				log.error("Verification mail could not be queued for email={}", EmailUtils.maskForLog(user.getEmail()), e);
			}
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
	
	@Transactional
	public BaseResponse<Void> verifyCode(VerifyCodeRequestDto dto){
		accountRateLimitGuard.checkOtpVerify(dto.email());
		final String email = EmailUtils.normalize(dto.email());
		// Redis verification is deliberately performed before branching on account
		// state. Unknown, already-verified and wrong-code requests therefore share
		// the same public error contract instead of becoming an account oracle.
		boolean valid = otpService.verifyOtp(email, dto.code());
		User user = userRepository.findByEmail(email).orElse(null);
		if (!valid || user == null || Boolean.TRUE.equals(user.getEmailVerified())) {
			throw invalidOtp();
		}
		
		// kullanici dogrula
		user.setEmailVerified(true);
		
		// kullanici venue degilse status'u aktif yap ve kaydet
		if (user.getStatus() != UserStatus.PENDING_VENUE_REQUEST
				&& user.getStatus() != UserStatus.PENDING_STUDIO_REQUEST) {
			user.setStatus(UserStatus.ACTIVE);
		}
		userRepository.save(user);
		
		// succes reponse
		return BaseResponse.<Void>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Dogrulama istegi basariyla islendi.")
		                   .build();
	}
	
	public BaseResponse<ResendCodeResponseDto> resendCode (ResendCodeRequestDto dto) {
		accountRateLimitGuard.checkOtpResend(dto.email());
		final String email = EmailUtils.normalize(dto.email());
		User user = userRepository.findByEmail(email).orElse(null);
		boolean eligibleForDelivery = user != null && !Boolean.TRUE.equals(user.getEmailVerified());
		
		// This isolated public claim alone drives status, TTL and cooldown for every
		// address. Internal registration/OTP state can therefore never change the
		// observable resend contract.
		OtpService.OtpIssueClaim publicClaim = otpService.acquireDecoyResendOtp(email);
		if (!publicClaim.acquired()) {
			long currentOtpTtl = otpService.getDecoyOtpTimeLeftSeconds(email);
			return BaseResponse.<ResendCodeResponseDto>builder()
			                   .success(false)
			                   .code(429)
			                   .message("cok sik istek: lutfen biraz bekleyip tekrar deneyin..")
			                   .data(new ResendCodeResponseDto(
					                   currentOtpTtl,
					                   false,
					                   publicClaim.cooldownSeconds()
			                   ))
			                   .build();
		}
		
		// Mail is sent only for an eligible account, while the public response stays
		// identical. Provider failures are intentionally not exposed because doing so
		// would reintroduce enumeration through the status code.
		OtpService.OtpIssueClaim deliveryClaim = eligibleForDelivery
				? otpService.acquireResendOtp(email)
				: null;
		if (deliveryClaim != null && deliveryClaim.acquired()) {
			try {
				otpMailService.sendVerificationMail(email, deliveryClaim.code());
			} catch (Exception e) {
				log.error("Verification mail resend could not be queued for email={}",
						EmailUtils.maskForLog(email), e);
			}
		}
		
		long ttl = otpService.getDecoyOtpTimeLeftSeconds(email);
		long cooldownLeft = otpService.getDecoyResendCooldownLeftSeconds(email);
		return BaseResponse.<ResendCodeResponseDto>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Hesap uygunsa dogrulama kodu e-posta adresine gonderilecektir.")
		                   // Delivery state is never disclosed on this public endpoint.
		                   .data(new ResendCodeResponseDto(ttl, false, cooldownLeft))
		                   .build();
	}

	private SoundConnectException invalidOtp() {
		return new SoundConnectException(
				ErrorType.VALIDATION_ERROR,
				List.of("Dogrulama kodu gecersiz veya suresi dolmus.")
		);
	}

	private User saveIdentityAndFlush(User user) {
		try {
			return userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException exception) {
			throw UserIdentityConflictMapper.map(exception);
		}
	}
	
}
