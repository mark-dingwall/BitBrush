package au.com.dingwall.mark.bitbrush.validation;

import java.util.UUID;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CanonicalUuidValidator implements ConstraintValidator<CanonicalUuid, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || isCanonical(value);
    }

    public static boolean isCanonical(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
