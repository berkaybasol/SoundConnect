package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import lombok.AllArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
/*
 Bu sinif User entity’sini, Spring Security’nin iç mekanizmasının “tanıyacağı” şekilde bir UserDetails nesnesine sarar.
 Böylece login olduğunda veya request ile kimlik doğrulandığında,
 Spring sistemin tamamı “kimin, hangi role/izne sahip olduğunu” doğru şekilde görebilir.
 */

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
		// Yetki adlarını string olarak tutacağımız set
		Set<String> authorityNames = new HashSet<>();
		
		// Rolleri güvenli şekilde yeni bir set'e çekiyoruz (ConcurrentModification hatası için önlem)
		Set<Role> roles = new HashSet<>(user.getRoles());
		for (Role role : roles) {
			// Role adını ekliyoruz
			authorityNames.add(role.getName());
			
			// Role'e ait izinleri güvenli şekilde yeni bir set'e çekiyoruz
			Set<Permission> permissionsOfRole = new HashSet<>(role.getPermissions());
			for (Permission permission : permissionsOfRole) {
				authorityNames.add(permission.getName());
			}
		}
		
		// Kullanıcının doğrudan sahip olduğu izinleri de güvenli şekilde yeni set'e çekiyoruz
		Set<Permission> directUserPermissions = new HashSet<>(user.getPermissions());
		for (Permission permission : directUserPermissions) {
			authorityNames.add(permission.getName());
		}
		
		// En son tüm string yetki adlarını SimpleGrantedAuthority'ye çeviriyoruz yoksa spring mevzuyu ayikmiyo
		return authorityNames.stream()
		                     .map(SimpleGrantedAuthority::new)
		                     .collect(Collectors.toSet());
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