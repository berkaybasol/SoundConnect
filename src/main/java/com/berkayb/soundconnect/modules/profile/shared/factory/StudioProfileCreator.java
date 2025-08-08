package com.berkayb.soundconnect.modules.profile.shared.factory;

import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ========================================
 * StudioProfileCreator
 * ========================================
 * Amaç:
 *   - Sadece "ROLE_STUDIO" rolündeki kullanıcılar için
 *     otomatik olarak StudioProfile oluşturur.
 *   - ProfileCreator interface'ini implement eder.
 *
 * Sistemde Ne Zaman Devreye Girer?
 *   - AuthService.register() sonrası
 *   - ProfileFactory.createProfileIfNeeded() çağrılır,
 *   - Kullanıcının rolü "ROLE_STUDIO" ise ProfileFactory, bu creator'ı bulup çağırır.
 */


@Service
@RequiredArgsConstructor
public class StudioProfileCreator implements ProfileCreator {
	private final StudioProfileService studioProfileService;
	
	@Override
	public void createProfile(User user) {
		StudioProfileSaveRequestDto dto = new StudioProfileSaveRequestDto(
				null,null,null,
				null,null,null,null,
				null,null);
		studioProfileService.createProfile(user.getId(), dto);
	}
	
	@Override
	public RoleEnum getSupportedRole() {
		return RoleEnum.ROLE_STUDIO;
	}
}