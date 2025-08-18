package com.berkayb.soundconnect.auth.dto.request;

import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.auth.validation.PasswordMatch;
import jakarta.validation.constraints.*;

@PasswordMatch
public record RegisterRequestDto(
		
		@NotBlank(message = "kullanıcı adı boş olamaz")
		@Size(min = 3, max = 30, message = "Kullanıcı adı 3 ile 30 karakter arasında olmalıdır.")
		String username,
		
		@Email(message = "Geçerli bir e-posta girin.")
		@NotBlank(message = "E-posta boş olamaz.")
		String email,
		
		@Size(min = 8, max = 20, message = "Şifreniz en az 8, en fazla 20 karakterden oluşmalıdır.")
		@NotBlank(message = "Şifre boş olamaz.")
		String password,
		
		@NotBlank(message = "Şifre tekrarı boş olamaz.")
		String rePassword,
		
		@NotNull(message = "Rol secilmelidir.")
		RoleEnum role

) {}