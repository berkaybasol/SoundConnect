package com.berkayb.soundconnect.modules.profile.shared.factory;

import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;

/**
 * ==========================
 * ProfileCreator Interface'i
 * ==========================
 * Amaç:
 *   - Her bir kullanıcı rolü (musician, venue, vs.) için
 *     "profil otomasyonu nasıl yapılır?" sorusunun cevabını
 *     tek bir interface ile standartlaştırmak.
 *   - Her yeni profil için sadece bir class yazarsın,
 *     sisteme otomatik eklenir.
 *
 * Kim, Ne Zaman Kullanır?
 *   - ProfileFactory, kullanıcının rolüne bakar, uygun ProfileCreator'ı çağırır.
 *   - Her profil creator, sadece kendi ilgilendiği role'de çalışır.
 *     (Örn. MusicianProfileCreator, sadece ROLE_MUSICIAN için)
 */
public interface ProfileCreator {
	
	/**
	 * Kullanıcıya ait profilin otomatik oluşturulmasını sağlar.
	 * Her implementasyonda "ben bu user'a hangi profil mantığıyla ne oluşturacağım?"
	 * kodu burada olur.
	 *
	 * ProfileFactory, uygun an geldiğinde (register sonrası)
	 * bu fonksiyonu çağırır!
	 */
	void createProfile(User user);
	
	/**
	 * Bu creator'ın ilgilendiği (desteklediği) rol.
	 * ProfileFactory, "roleEnum" ile map'lediği için
	 * bu fonksiyondan dönen role'a göre creator'ı çağırır.
	 * (Örn. MusicianProfileCreator → ROLE_MUSICIAN)
	 */
	RoleEnum getSupportedRole();
}