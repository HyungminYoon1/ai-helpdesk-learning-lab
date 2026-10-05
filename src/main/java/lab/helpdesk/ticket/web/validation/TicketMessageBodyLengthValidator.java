package lab.helpdesk.ticket.web.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lab.helpdesk.ticket.TicketMessage;

public class TicketMessageBodyLengthValidator
        implements ConstraintValidator<TicketMessageBodyLength, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null과 공백뿐인 입력은 별도의 @NotBlank가 거부한다.
        return value == null || TicketMessage.bodyCodePointCount(value)
                <= TicketMessage.MAX_BODY_CODE_POINTS;
    }
}
