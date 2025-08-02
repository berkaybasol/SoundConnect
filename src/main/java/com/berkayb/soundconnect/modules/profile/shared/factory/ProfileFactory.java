package com.berkayb.soundconnect.modules.profile.shared.factory;

import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * =====================================
 * ProfileFactory
 * =====================================
 * Amaç:
 *   - Sistemde hangi rolün, hangi profile creator'ı ile eşleştiğini merkezi olarak yönetmek.
 *   - Her yeni profil eklenince sadece yeni ProfileCreator class'ı yazılır,
 *     ProfileFactory hiçbir değişiklik istemez!
 *   - Register sonrası kullanıcıya uygun profili otomatik açmak için kullanılır.
 *
 * Kim, Ne Zaman Çağırır?
 *   - Sadece AuthService.register() sonrası çağrılır!
 *   - ProfileFactory, rolü alır, uygun profile creator'ı bulur ve çağırır.
 */
@Service
public class ProfileFactory {
	
	// Map: Hangi role'a hangi profile creator eşleşir? (ROLE_MUSICIAN -> MusicianProfileCreator)
	private final Map<RoleEnum, ProfileCreator> creatorMap = new HashMap<>();
	
	/**
	 * Constructor.
	 * Spring, tüm ProfileCreator implementasyonlarını (ör: MusicianProfileCreator) otomatik olarak buraya inject eder!
	 * Bu, yeni bir profil ekleyince elle factory'ye yazma gereğini ortadan kaldırır.
	 */
	@Autowired
	public ProfileFactory(List<ProfileCreator> creators) {
		// Sistemdeki tüm profile creator'ları map'e atıyoruz.
		// getSupportedRole() ile her bir creator kendi rolünü belirtiyor.
		for (ProfileCreator creator : creators) {
			creatorMap.put(creator.getSupportedRole(), creator);
		}
		// Örneğin: creatorMap = {ROLE_MUSICIAN -> MusicianProfileCreator}
	}
	
	/**
	 * Ana fonksiyon: Kullanıcının rolüne göre, uygun profil creator'ı var mı diye bakar.
	 * Eğer varsa profil otomasyonu çalıştırır.
	 * @param user Yeni oluşturulan kullanıcı (register sonrası)
	 * @param roleEnum Kullanıcının rolü (örn. ROLE_MUSICIAN)
	 */
	public void createProfileIfNeeded(User user, RoleEnum roleEnum) {
		// Map'te o role için bir creator var mı?
		ProfileCreator creator = creatorMap.get(roleEnum);
		if (creator != null) {
			// Varsa, profili oluşturur.
			// Bu çağrı zincirinde: Factory -> Creator -> Service (veya DB)
			creator.createProfile(user);
		}
		// Eğer yoksa (ör: ROLE_USER), hiçbir şey yapılmaz.
	}
}