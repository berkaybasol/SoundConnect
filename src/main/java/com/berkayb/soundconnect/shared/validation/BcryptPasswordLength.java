package com.berkayb.soundconnect.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = BcryptPasswordLengthValidator.class)
@Target({
		ElementType.METHOD,
		ElementType.FIELD,
		ElementType.ANNOTATION_TYPE,
		ElementType.CONSTRUCTOR,
		ElementType.PARAMETER,
		ElementType.TYPE_USE,
		ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
public @interface BcryptPasswordLength {
	int MAX_UTF8_BYTES = 72;

	String message() default "Şifre en fazla 72 UTF-8 byte olabilir.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
