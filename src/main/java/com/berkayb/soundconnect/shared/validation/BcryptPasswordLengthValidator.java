package com.berkayb.soundconnect.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class BcryptPasswordLengthValidator
		implements ConstraintValidator<BcryptPasswordLength, CharSequence> {

	@Override
	public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
		if (value == null) {
			return true;
		}
		return value.toString().getBytes(StandardCharsets.UTF_8).length
				<= BcryptPasswordLength.MAX_UTF8_BYTES;
	}
}
