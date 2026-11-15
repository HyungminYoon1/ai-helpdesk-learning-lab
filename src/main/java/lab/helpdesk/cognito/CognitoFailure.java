package lab.helpdesk.cognito;

import org.springframework.http.HttpStatus;

final class CognitoFailure extends RuntimeException {
    enum Code {
        INVALID_INPUT(HttpStatus.BAD_REQUEST),
        ONBOARDING_STEP_REQUIRED(HttpStatus.CONFLICT),
        INVITATION_UNAVAILABLE(HttpStatus.FORBIDDEN),
        SIGN_IN_REQUIRED(HttpStatus.FORBIDDEN),
        FRESH_AUTHENTICATION_REQUIRED(HttpStatus.FORBIDDEN),
        ACCOUNT_ADMIN_REQUIRED(HttpStatus.FORBIDDEN),
        EMAIL_CODE_REJECTED(HttpStatus.BAD_REQUEST),
        COGNITO_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

        final HttpStatus status;

        Code(HttpStatus status) {
            this.status = status;
        }
    }

    private final Code code;

    CognitoFailure(Code code) {
        super(code.name());
        this.code = code;
    }

    Code code() {
        return code;
    }
}
