package com.berkayb.soundconnect.auth.validation;

import com.berkayb.soundconnect.auth.dto.request.UserRegisterRequestDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordMatchValidator implements ConstraintValidator<PasswordMatch, UserRegisterRequestDto> {
	
	@Override
	public boolean isValid(UserRegisterRequestDto dto, ConstraintValidatorContext context) {
		if (dto.password() == null || dto.rePassword() == null) {
			return false; // Şifrelerin null olup olmadığını kontrol et
		}
		
		if (!dto.password().equals(dto.rePassword())) {  
			context.disableDefaultConstraintViolation();
			context.buildConstraintViolationWithTemplate("Şifreler uyuşmuyor.")
			       .addPropertyNode("rePassword")
			       .addConstraintViolation();
			return false;
		}
		return true;
	}
}