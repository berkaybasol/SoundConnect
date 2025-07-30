package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Bu sinifin amaci Spring Security login ve JWT çözümleme sürecinde,
 * username ile DB’den kullanıcıyı bulup, sistemin tanıyacağı formata sarmak.
 */
// Bu sinif, Spring Security'de kullaniciyi nasil bulacagini ogretir.
// username ile veritabanindan kullaniciyi ceker ve geriye UserDetails doner
// bu sayede login ve token cozumleme islemleri duzgun calisir


@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
	private final UserRepository userRepository;
	
	// verilen username ile veritabanindan kullaniciyi buluruz
	// bulamazsak springe ozel exception firlatilir
	// bulursak kullaniciyi spring'in anlayacagi UserDetails formatina cevirip doneriz.
	
	@Override
	@Transactional  // loadUserByUsername metodu boyunca DB connection ve session açık kalır
					// Roller/izinler rahatça çekilir, hata fırlamaz.
	
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		User user = userRepository.findByUsername(username)
				.orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		return new UserDetailsImpl(user);
	}
	
	// bunu kullancaz ustteki method yerine
	public UserDetails loadUserById(UUID userId) {
		User user = userRepository.findById(userId)
		                          .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		return new UserDetailsImpl(user);
	}
}