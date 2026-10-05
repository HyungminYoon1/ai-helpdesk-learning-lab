package lab.helpdesk.ticket.web.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TicketMessageBodyLengthValidator.class)
public @interface TicketMessageBodyLength {
    String message() default "body must not exceed 2000 Unicode code points excluding edge whitespace";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
