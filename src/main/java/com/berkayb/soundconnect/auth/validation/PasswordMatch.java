package com.berkayb.soundconnect.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PasswordMatchValidator.class)
@Target(ElementType.TYPE) // Class veya Record'lara uygulanabilir
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordMatch {
	String message() default "Şifreler uyuşmuyor.";
	Class<?>[] groups() default {};
	Class<? extends Payload>[] payload() default {};
}