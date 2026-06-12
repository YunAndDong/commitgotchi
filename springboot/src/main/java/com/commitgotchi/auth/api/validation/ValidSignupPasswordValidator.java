package com.commitgotchi.auth.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class ValidSignupPasswordValidator implements ConstraintValidator<ValidSignupPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        int characterCount = value.codePointCount(0, value.length());
        return characterCount >= 12
                && characterCount <= 64
                && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
