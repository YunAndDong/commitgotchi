package com.commitgotchi.auth.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class ValidSignupEmailValidator implements ConstraintValidator<ValidSignupEmail, String> {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@.]+(?:\\.[^\\s@.]+)*@[^\\s@.]+(?:\\.[^\\s@.]+)+$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty()
                && trimmed.length() <= 254
                && EMAIL_PATTERN.matcher(trimmed).matches();
    }
}
