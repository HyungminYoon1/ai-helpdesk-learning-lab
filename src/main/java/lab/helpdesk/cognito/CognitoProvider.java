package lab.helpdesk.cognito;

interface CognitoProvider {
    void requestEmailCode(String accessToken);
    void verifyEmailCode(String accessToken, String code);
    void requireTotpOnly();
}
