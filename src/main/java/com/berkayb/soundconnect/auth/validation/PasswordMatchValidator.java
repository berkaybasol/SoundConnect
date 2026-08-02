package com.berkayb.soundconnect.auth.validation;

import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.util.Objects;

/**
 * Validates password confirmation on any request object exposing the configured
 * password properties. The default property names preserve the registration
 * request contract while allowing password-reset requests to reuse the policy.
 */
public class PasswordMatchValidator implements ConstraintValidator<PasswordMatch, Object> {

	private String passwordField;
	private String confirmationField;
	private String message;

	@Override
	public void initialize(PasswordMatch annotation) {
		passwordField = annotation.passwordField();
		confirmationField = annotation.confirmationField();
		message = annotation.message();
	}

	@Override
	public boolean isValid(Object value, ConstraintValidatorContext context) {
		if (value == null) {
			return true;
		}

		BeanWrapper wrapper = new BeanWrapperImpl(value);
		if (!wrapper.isReadableProperty(passwordField)
				|| !wrapper.isReadableProperty(confirmationField)) {
			throw new ConstraintDeclarationException(
					"@PasswordMatch requires readable password and confirmation properties");
		}

		Object password = wrapper.getPropertyValue(passwordField);
		Object confirmation = wrapper.getPropertyValue(confirmationField);
		if (password != null && Objects.equals(password, confirmation)) {
			return true;
		}

		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(message)
				.addPropertyNode(confirmationField)
				.addConstraintViolation();
		return false;
	}
}
