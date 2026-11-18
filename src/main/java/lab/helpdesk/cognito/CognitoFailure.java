package lab.helpdesk.cognito;

import org.springframework.http.HttpStatus;

final class CognitoFailure extends RuntimeException {
    enum Code {
        INVALID_INPUT(HttpStatus.BAD_REQUEST),
        ONBOARDING_STEP_REQUIRED(HttpStatus.CONFLICT),
        INVITATION_UNAVAILABLE(HttpStatus.FORBIDDEN),
        SIGN_IN_REQUIRED(HttpStatus.FORBIDDEN),
        FRESH_AUTHENTICATION_REQUIRED(HttpStatus.FORBIDDEN),
        ACCOUNT_DISABLED(HttpStatus.FORBIDDEN),
        ACCOUNT_RELOGIN_REQUIRED(HttpStatus.FORBIDDEN),
        ACCOUNT_ADMIN_REQUIRED(HttpStatus.FORBIDDEN),
        ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND),
        ACCOUNT_STATUS_VERSION_CONFLICT(HttpStatus.CONFLICT),
        ACCOUNT_RECOVERY_PENDING(HttpStatus.CONFLICT),
        ACCOUNT_RECOVERY_REQUEST_CONFLICT(HttpStatus.CONFLICT),
        ACCOUNT_RECOVERY_NOT_FOUND(HttpStatus.NOT_FOUND),
        ACCOUNT_RECOVERY_NOT_READY(HttpStatus.CONFLICT),
        ACCOUNT_RECOVERY_IN_PROGRESS(HttpStatus.CONFLICT),
        ACCOUNT_RECOVERY_PROVIDER_UNCONFIRMED(HttpStatus.CONFLICT),
        ACCOUNT_RECOVERY_PROOF_REQUIRED(HttpStatus.CONFLICT),
        INVALID_TEMPORARY_PASSWORD(HttpStatus.BAD_REQUEST),
        FIRST_ADMINISTRATOR_PROTECTED(HttpStatus.CONFLICT),
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
