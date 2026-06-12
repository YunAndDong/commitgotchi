package com.commitgotchi.auth.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidSignupPasswordValidator.class)
public @interface ValidSignupPassword {
    String message() default "Password does not satisfy the signup policy.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
