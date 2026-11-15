package lab.helpdesk.cognito;

import java.util.List;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExpiredCodeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.OAuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolMfaType;

import static lab.helpdesk.cognito.CognitoFailure.Code.*;

final class AwsCognitoProvider implements CognitoProvider {
    private final CognitoIdentityProviderClient client;
    private final CognitoSettings settings;

    AwsCognitoProvider(CognitoIdentityProviderClient client, CognitoSettings settings) {
        this.client = client;
        this.settings = settings;
    }

    @Override
    public void requestEmailCode(String accessToken) {
        try {
            client.getUserAttributeVerificationCode(request -> request.accessToken(accessToken).attributeName("email"));
        } catch (SdkException ex) {
            throw new CognitoFailure(COGNITO_UNAVAILABLE);
        }
    }

    @Override
    public void verifyEmailCode(String accessToken, String code) {
        try {
            client.verifyUserAttribute(request -> request.accessToken(accessToken).attributeName("email").code(code));
        } catch (CodeMismatchException | ExpiredCodeException | NotAuthorizedException ex) {
            throw new CognitoFailure(EMAIL_CODE_REJECTED);
        } catch (SdkException ex) {
            throw new CognitoFailure(COGNITO_UNAVAILABLE);
        }
    }

    @Override
    public void requireTotpOnly() {
        try {
            var mfa = client.getUserPoolMfaConfig(request -> request.userPoolId(settings.poolId()));
            if (mfa.mfaConfiguration() != UserPoolMfaType.ON || mfa.softwareTokenMfaConfiguration() == null
                    || !Boolean.TRUE.equals(mfa.softwareTokenMfaConfiguration().enabled())
                    || mfa.smsMfaConfiguration() != null || mfa.emailMfaConfiguration() != null
                    || mfa.webAuthnConfiguration() != null) {
                throw new CognitoFailure(COGNITO_UNAVAILABLE);
            }
            var app = client.describeUserPoolClient(request -> request
                    .userPoolId(settings.poolId()).clientId(settings.clientId())).userPoolClient();
            if (app == null || !app.supportedIdentityProviders().equals(List.of("COGNITO"))
                    || !Boolean.TRUE.equals(app.allowedOAuthFlowsUserPoolClient())
                    || !app.allowedOAuthFlows().equals(List.of(OAuthFlowType.CODE))) {
                throw new CognitoFailure(COGNITO_UNAVAILABLE);
            }
        } catch (SdkException ex) {
            throw new CognitoFailure(COGNITO_UNAVAILABLE);
        }
    }
}
