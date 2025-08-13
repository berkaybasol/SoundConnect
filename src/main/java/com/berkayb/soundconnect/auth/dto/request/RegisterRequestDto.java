package com.berkayb.soundconnect.auth.dto.request;

import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.auth.validation.PasswordMatch;
import jakarta.validation.constraints.*;

@PasswordMatch
public record RegisterRequestDto(
		
		@NotBlank(message = "Username alanı boş bırakılamaz.")
		@Size(min = 3, max = 20, message = "Kullanıcı adı 3 ile 20 karakter arasında olmalıdır.")
		String username,
		
		@Email(message = "Geçerli bir e-posta girin.")
		@NotBlank(message = "E-posta boş olamaz.")
		String email,
		
		@Size(min = 8, max = 20, message = "Şifreniz en az 8, en fazla 20 karakterden oluşmalıdır.")
		@NotBlank(message = "Şifre boş olamaz.")
		@Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#$%*^&+=]).{8,20}$", //FIXME Bu iskence ya bunu basitlestir
				message = "Şifre en az bir büyük harf, bir küçük harf, bir rakam ve bir özel karakter içermelidir.")
		String password,
		
		@NotBlank(message = "Şifre tekrarı boş olamaz.")
		String rePassword,
		
		@NotNull(message = "Rol secilmelidir.")
		RoleEnum role

) {}