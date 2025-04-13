package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.user.entity.User;
import com.berkayb.soundconnect.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// Bu sinif, Spring Security'de kullaniciyi nasil bulacagini ogretir.
// username ile veritabanindan kullaniciyi ceker ve geriye UserDetails doner
// bu sayede login ve token cozumleme islemleri duzgun calisir


@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
	private final UserRepository userRepository;
	
	// verilen username ile veritabanindan kullaniciyi buluruz
	// bulamazsak springe ozel exception firlatilir
	// bulursak kullaniciyi sprin'in anlatyacagi UserDetails formatina cevirip doneriz.
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		User user = userRepository.findByUsername(username)
				.orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		return new UserDetailsImpl(user);
	}
}