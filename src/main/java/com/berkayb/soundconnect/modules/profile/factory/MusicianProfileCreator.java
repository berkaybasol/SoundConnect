package com.berkayb.soundconnect.modules.profile.factory;

import com.berkayb.soundconnect.modules.profile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ========================================
 * MusicianProfileCreator
 * ========================================
 * Amaç:
 *   - Sadece "ROLE_MUSICIAN" rolündeki kullanıcılar için
 *     otomatik olarak MusicianProfile oluşturur.
 *   - ProfileCreator interface'ini implement eder.
 *
 * Sistemde Ne Zaman Devreye Girer?
 *   - AuthService.register() sonrası
 *   - ProfileFactory.createProfileIfNeeded() çağrılır,
 *   - Kullanıcının rolü "ROLE_MUSICIAN" ise ProfileFactory, bu creator'ı bulup çağırır.
 */
@Service // Spring otomatik olarak injectable bean haline getirir
@RequiredArgsConstructor
public class MusicianProfileCreator implements ProfileCreator {
	
	// MusicianProfileService: Profili asıl DB'ye kaydeden servis.
	private final MusicianProfileService musicianProfileService;
	
	/**
	 * Kullanıcıya boş bir müzisyen profili açar.
	 * - Profilin diğer alanları (stageName, bio, vs.) kullanıcı login olunca doldurulacak.
	 * - Her zaman ProfileFactory tarafından tetiklenir, doğrudan controller vs. kullanmaz.
	 */
	@Override
	public void createProfile(User user) {
		MusicianProfileSaveRequestDto dto = new MusicianProfileSaveRequestDto(
				null, null, null, null, null, null, null, null);
		musicianProfileService.createProfile(user.getId(), dto);
	}
	
	/**
	 * Bu creator sadece "ROLE_MUSICIAN" için çalışır.
	 * ProfileFactory, map'lediği rol ile bu fonksiyondan dönen role'u eşleştirir.
	 */
	@Override
	public RoleEnum getSupportedRole() {
		return RoleEnum.ROLE_MUSICIAN;
	}
}