package com.berkayb.soundconnect.auth.dto.request;

import com.berkayb.soundconnect.modules.user.enums.City;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import com.berkayb.soundconnect.auth.validation.PasswordMatch;
import jakarta.validation.constraints.*;

import java.util.UUID;

@PasswordMatch
public record RegisterRequestDto(
		
		@NotBlank(message = "Username alanı boş bırakılamaz.")
		@Size(min = 3, max = 20, message = "Kullanıcı adı 3 ile 20 karakter arasında olmalıdır.")
		String username,
		
		@Email(message = "Geçerli bir e-posta girin.")
		@NotBlank(message = "E-posta boş olamaz.")
		String email,
		
		@Pattern(regexp = "^((\\+90)?[1-9][0-9]{9})$",
				message = "Telefon numarası geçerli formatta olmalıdır. Örn: +905551234567 veya 05551234567")
		String phone,
		
		@NotNull(message = "Cinsiyet boş olamaz.")
		Gender gender,
		
		@NotNull(message = "Rol boş olamaz.")
		UUID roleId,
		
		@NotNull(message = "Şehir boş olamaz.")
		City city,
		
		@Size(min = 8, max = 20, message = "Şifreniz en az 8, en fazla 20 karakterden oluşmalıdır.")
		@NotBlank(message = "Şifre boş olamaz.")
		@Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#$%*^&+=]).{8,20}$",
				message = "Şifre en az bir büyük harf, bir küçük harf, bir rakam ve bir özel karakter içermelidir.")
		String password,
		
		@NotBlank(message = "Şifre tekrarı boş olamaz.")
		String rePassword

) {}