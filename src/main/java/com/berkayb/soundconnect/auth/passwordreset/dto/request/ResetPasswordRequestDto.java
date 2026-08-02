package com.berkayb.soundconnect.auth.passwordreset.dto.request;

import com.berkayb.soundconnect.auth.validation.PasswordMatch;
import com.berkayb.soundconnect.shared.validation.BcryptPasswordLength;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@PasswordMatch
public record ResetPasswordRequestDto(
		@JsonAlias("email")
		@NotBlank(message = "Kullanıcı adı veya e-posta boş olamaz.")
		@Size(max = 254, message = "Kullanıcı adı veya e-posta en fazla 254 karakter olabilir.")
		String identifier,

		@NotBlank(message = "Doğrulama kodu boş olamaz.")
		@Pattern(regexp = "\\d{6}", message = "Doğrulama kodu 6 haneli olmalıdır.")
		String code,

		@NotBlank(message = "Şifre boş olamaz.")
		@Size(min = 8, max = 72, message = "Şifreniz en az 8, en fazla 72 karakterden oluşmalıdır.")
		@BcryptPasswordLength
		String password,

		@NotBlank(message = "Şifre tekrarı boş olamaz.")
		String rePassword
) {
}
