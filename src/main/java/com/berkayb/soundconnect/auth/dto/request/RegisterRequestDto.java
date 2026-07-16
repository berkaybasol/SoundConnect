package com.berkayb.soundconnect.auth.dto.request;

import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.auth.validation.PasswordMatch;
import com.berkayb.soundconnect.shared.validation.BcryptPasswordLength;
import jakarta.validation.constraints.*;

@PasswordMatch
public record RegisterRequestDto(
		
		@NotBlank(message = "kullanıcı adı boş olamaz")
		@Size(min = 3, max = 30, message = "Kullanıcı adı 3 ile 30 karakter arasında olmalıdır.")
		String username,
		
		@Email(message = "Geçerli bir e-posta girin.")
		@NotBlank(message = "E-posta boş olamaz.")
		@Size(max = 254, message = "E-posta en fazla 254 karakter olabilir.")
		String email,
		
		@Size(min = 8, max = 72, message = "Şifreniz en az 8, en fazla 72 karakterden oluşmalıdır.")
		@BcryptPasswordLength
		@NotBlank(message = "Şifre boş olamaz.")
		String password,
		
		@NotBlank(message = "Şifre tekrarı boş olamaz.")
		String rePassword,
		
		@NotNull(message = "Rol secilmelidir.")
		RoleEnum role,
		
		
		// role venue secilirse
        String venueName,
		String venueAddress,
		String phone,
		String cityId,
		String districtId,
		String neighborhoodId
) {}
