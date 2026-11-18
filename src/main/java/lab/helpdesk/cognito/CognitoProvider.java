package lab.helpdesk.cognito;

interface CognitoProvider {
    void requestEmailCode(String accessToken);
    void verifyEmailCode(String accessToken, String code);
    void requireTotpOnly();
    void requireAdminOnlyRecovery();
    RecoveryUser recoveryUser(String issuer, String subject);
    void signOutEverywhere(String username);
    void setTemporaryPassword(String username, String password);
    void removeTotp(String username);

    record RecoveryUser(String username, boolean confirmed, boolean passwordChangeRequired, boolean totpRegistered) {
        @Override public String toString() { return "CognitoRecoveryUser[redacted]"; }
    }
}
