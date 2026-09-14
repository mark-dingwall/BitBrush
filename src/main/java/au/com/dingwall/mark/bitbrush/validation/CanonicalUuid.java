package au.com.dingwall.mark.bitbrush.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = CanonicalUuidValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface CanonicalUuid {
    String message() default "Must be a canonical UUID";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
