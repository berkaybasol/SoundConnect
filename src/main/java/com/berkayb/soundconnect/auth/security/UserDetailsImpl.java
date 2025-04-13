package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.user.entity.User;
import lombok.AllArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.stream.Collectors;

@AllArgsConstructor
public class UserDetailsImpl implements UserDetails {
	private final User user;
	
	// Authenticated kullanıcının tüm User entity bilgilerine erişmek için getter methodu
	// @Getter işe yaramıyor çünkü UserDetailsImpl sınıfı zaten Spring Security'nin bir interface'ini implement ediyor.
	// User alanı doğrudan serialize edilemeyeceği için genelde bu gibi özel methodlarla erişilir
	public User getUser() {
		return this.user;
	}
	
	// Spring Security'de bir kullanıcının sahip olduğu yetkileri (roller veya izinler) temsil eden interface: GrantedAuthority
	// Bu metot, kullanıcı doğrulandıktan sonra hangi işlemleri yapabileceğini belirlemek için çalışır.
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		// Kullanicinin sahip oldugu izinleri alip stream"e donusturuyoruz
		return user.getPermissions()
				.stream()
				// Her bir permission nesnesini, GrantedAuthority tipine çeviriyoruz.
				// Lambda ifadesi ile, getAuthority() metodu permission.getName() değerini dönecek şekilde yapılandırılıyor
				.map(permission -> new SimpleGrantedAuthority(permission.getName()))
		           // Stream"den tekrar listeye ceviriyoruz.
				.collect(Collectors.toList());
	}
	
	@Override
	public String getPassword() {
		// Entity'den sifre aliyoruz
		return user.getPassword();
	}
	
	@Override
	public String getUsername() {
		// Entity'den username aliyoruz
		return user.getUsername();
	}
	
	@Override
	public boolean isAccountNonExpired() {
		return true; // Sifre suresi gecmis mi?
	}
	
	@Override
	public boolean isAccountNonLocked() {
		return true; // Hesap kitli mi?
	}
	
	@Override
	public boolean isCredentialsNonExpired() {
		return true; // Sifre suresi gecmis mi?
	}
	
	@Override
	public boolean isEnabled() {
		return true; // Hesap aktif mi?
	}
}