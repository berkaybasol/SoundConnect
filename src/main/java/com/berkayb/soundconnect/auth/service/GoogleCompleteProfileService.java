package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.service.UserService;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleCompleteProfileService {
	private final UserService userService;
	private final RoleRepository roleRepository;
	private final ProfileFactory profileFactory;
	private final UserEntityFinder userEntityFinder;
	private final UserRepository userRepository;
	
	
	/**
	 * google ile giris yapan ve profilini tamamlamak isteyen kullaniciya rol atar ve profilini olusturur.
	 * @param userId kimligi dogrulanmis user'in id'si (JWT'den gelir)
	 * @param dto kullanicinin sectigi rol.
	 */
	public void completeProfileWithRole(UUID userId, GoogleCompleteProfileRequestDto dto){
		// kullaniciyi bul
		User user = userEntityFinder.getUser(userId);
		
		// eger zaten role atanmissa hata ver(tekrar secemesin)
		if (user.getRoles() != null && !user.getRoles().isEmpty()){
			log.warn("kullanici zaten role sahip: {}", user.getEmail());
			throw new SoundConnectException(ErrorType.USER_ALREADY_REGISTERED);
		}
		
		// RoleEnum'dan secilen enumu al
		RoleEnum selectedRoleEnum = dto.role();
		Role selectedRole = roleRepository.findByName(selectedRoleEnum.name())
				.orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
		
		// rolu ata
		user.setRoles(Set.of(selectedRole));
		userRepository.save(user);
		
		// venue ise otomatik profil acilmasin.
		if (selectedRoleEnum != RoleEnum.ROLE_VENUE) {
			// venue haricinde bir sey ise otomatik profile ac
			profileFactory.createProfileIfNeeded(user, selectedRoleEnum);
			log.info("Google ile gelen kullaniciya rol ve profil atandi. Email: {} - Rol: {}", user.getEmail(), selectedRoleEnum.name());
		} else {
			log.info("Venue Rolu secildi, profil admin onayindan sonra olusturulacak, Email: {}", user.getEmail());
		}
	}
}