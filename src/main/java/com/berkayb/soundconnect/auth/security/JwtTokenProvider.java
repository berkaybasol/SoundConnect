package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.*;
import java.util.stream.Collectors;

// bu sinif token uretir, cozer ve dogrular. tum token operasyonlarinin merkezidir.
@Slf4j
@Component // Spring'e bean oldugunu tanitir bunun sonucunda container'a eklenir.
public class JwtTokenProvider {
	
	// application.yml icinde tanimladigimiz JWT secret keyi cekiyoruz
	@Value("${app.jwt.secret}")
	private String jwtSecret;
	
	// application,yml icinde tanimladigimiz token suresini cekiyoruz.
	@Value("${app.jwt.expiration}")
	private long jwtExpiration;
	
	// application.yml icinde tanimladigim issueri yani tokenin kimin olusturdugunu belirtir.
	@Value("${app.jwt.issuer}")
	private String jwtIssuer;
	
	// JWT secret'i HMAC imzasi icin gerekli olan key objesine donustururuz. (byte array tabanli)
	// HMAC, token'in backend tarafindan uretildigini ve yolda bozulmadigini garantileyen imzalama yontemidir.
	private Key getSigningKey() {
		return Keys.hmacShaKeyFor(jwtSecret.getBytes());
	}
	
	// Token uretme metodu (login sonrasi bu cagiriliyor)
	// Kullanici verisi Security zincirinde UserDetils soyutlamasi ile tasinir.
	// Token uretirken standart User yerine UserDetails kullaniliyor.
	public String generateToken(UserDetailsImpl userDetails) {
		// Authenticated kullanicisinin veritabanindaki User entity nesnesini aliyoruz
		User user = userDetails.getUser();
		
		// Izinleri string listesine ceviriyoruz.
		List<String> permissions = user.getRoles().stream()
		                               .flatMap(role -> role.getPermissions().stream())
		                               .map(Permission::getName)
		                               .distinct()
		                               .collect(Collectors.toList());
		
		
		// Rolleri String listesine ceviriyoruz
		List<String> roles = user.getRoles()
				.stream()
				.map(Role::getName)
				.collect(Collectors.toList());
		
		// JWT claim'leri map'ine ekleniyor
		Map<String, Object> claims = new HashMap<>();
		claims.put("roles", roles);
		claims.put("permissions", permissions);
		
		return Jwts.builder()
				// JWT'nin payload kismina ozel alanlar ekliyoruz. (roller ve izinler gibi)
				// "roles" ve "permissions" alanlari daha sonra token cozumlenirken guvenlik kontrolu icin kullanilcak
				.setClaims(claims)
				.setSubject(userDetails.getUser().getId().toString()) // kullaniciyi subject yani token sahibi olarak token icine koyar
		/*
		 * Subject = JWT token içindeki "kim" kısmıdır (token kime ait?).
		 * Örneğin, JWT’nin base64 çözüldüğünde yapısı şu şekildedir:
		 *
		 * Header:
		 * {
		 *   "alg": "HS512",
		 *   "typ": "JWT"
		 * }
		 *
		 * Payload:
		 * {
		 *   "sub": "berkay",        // Burada sub alanını doldurmuş oluyoruz
		 *   "roles": "ROLE_USER",
		 *   "exp": 17112345678
		 * }
		 *
		 * .setSubject(userDetails.getUsername()) çağrısı ile "sub" alanına kullanıcının username bilgisini koyarız.
		 */
				.setIssuer(jwtIssuer) // token'ı hangi servis oluşturduysa onu belirtir (örneğin: "soundconnect-auth")
		 
		/***
		 * Kullanıcının rollerini (authorities) JWT token’ın payload kısmına "roles" adıyla ekliyoruz.
		 * getAuthorities() → ROLE bilgilerini döner, stream ile tek tek gezip sadece role isimlerini alıyoruz.
		 */
				.setIssuedAt(new Date()) // suanki zaman
		           // tokenin gecerlilik suresi. suanki zamana yml'dan gelen sure eklenir.
				.setExpiration(new Date(System.currentTimeMillis() + jwtExpiration))
				.signWith(getSigningKey(), SignatureAlgorithm.HS256)
				.compact(); // tokeni string formatina cevirir. frontende bunu gondeririz
	}
	
	// ortak tyoken cozumleme islemi (parse + claim cikarma)
	private Claims extractAllClaims(String token) {
		return Jwts.parserBuilder()
				.setSigningKey(getSigningKey()) // token imzasini dogrulamak icin key veriyoruz
				.requireIssuer(jwtIssuer)
				.build()
				.parseClaimsJws(token)// token parse edilir ->> imza ve sure kontrolu yapilir
				.getBody(); // eger token gecerliyse payloaddan claim kismi alinir.
	}
	
	
	
	// Token'in icerisiten subject bilgisini almak icin kullanilir.
	// token cozulmeden once imzasi kontrol edilir ve gecerliyse payload kismindan subject alinir.
	public UUID getUserIdFromToken(String token) {
		return UUID.fromString(extractAllClaims(token).getSubject()); // generateToken'da koydumuz sub alanini cekiyoruz
	}
	
	// token gecerli mi diye kontrol ettigimiz metod (filtrede kullanicaz)
	public boolean validateToken(String token) {
		try {
			extractAllClaims(token); // token parse edilebiliyorsa gecerli kabul ediyoruz
			return true;
		}
		catch (ExpiredJwtException e) {
			log.warn("Expired JWT token");
		}
		catch (UnsupportedJwtException e) {
			log.warn("Unsupported JWT token");
		}
		catch (MalformedJwtException e) {
			log.warn("Malformed JWT token"); // token deforme olmus (bozuk)
		}
		catch (SignatureException e) {
			log.warn("Invalid JWT token");
		}
		catch (IllegalArgumentException e) {
			log.warn("JWT claims string is empty or null");
		}
		return false;
	}
}