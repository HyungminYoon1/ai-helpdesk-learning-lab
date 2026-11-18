package lab.helpdesk.cognito;

import java.util.List;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExpiredCodeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.OAuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.PasswordHistoryPolicyViolationException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RecoveryOptionNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolMfaType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserStatusType;

import static lab.helpdesk.cognito.CognitoFailure.Code.*;

final class AwsCognitoProvider implements CognitoProvider {
    private final CognitoIdentityProviderClient client;
    private final CognitoIdentityProviderClient recoveryMutations;
    private final CognitoSettings settings;

    AwsCognitoProvider(CognitoIdentityProviderClient client, CognitoIdentityProviderClient recoveryMutations,
            CognitoSettings settings) {
        this.client = client;
        this.recoveryMutations = recoveryMutations;
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

    @Override
    public void requireAdminOnlyRecovery() {
        try {
            var pool = client.describeUserPool(request -> request.userPoolId(settings.poolId())).userPool();
            var recovery = pool == null ? null : pool.accountRecoverySetting();
            if (recovery == null || !recovery.recoveryMechanisms().stream().map(method -> method.name()).toList()
                    .equals(List.of(RecoveryOptionNameType.ADMIN_ONLY))) {
                throw new CognitoFailure(COGNITO_UNAVAILABLE);
            }
        } catch (SdkException ex) {
            throw new CognitoFailure(COGNITO_UNAVAILABLE);
        }
    }

    @Override
    public RecoveryUser recoveryUser(String issuer, String subject) {
        if (!settings.issuer().equals(issuer)) throw new CognitoFailure(COGNITO_UNAVAILABLE);
        try {
            var user = client.adminGetUser(request -> request.userPoolId(settings.poolId()).username(subject));
            boolean sameSubject = user.userAttributes().stream()
                    .anyMatch(attribute -> "sub".equals(attribute.name()) && subject.equals(attribute.value()));
            if (!sameSubject || !Boolean.TRUE.equals(user.enabled()) || user.username() == null || user.username().isBlank()
                    || user.userMFASettingList().stream().anyMatch(factor -> !"SOFTWARE_TOKEN_MFA".equals(factor))) {
                throw new CognitoFailure(COGNITO_UNAVAILABLE);
            }
            return new RecoveryUser(user.username(), user.userStatus() == UserStatusType.CONFIRMED,
                    user.userStatus() == UserStatusType.FORCE_CHANGE_PASSWORD,
                    user.userMFASettingList().contains("SOFTWARE_TOKEN_MFA"));
        } catch (SdkException ex) {
            throw new CognitoFailure(COGNITO_UNAVAILABLE);
        }
    }

    @Override
    public void signOutEverywhere(String username) {
        try {
            recoveryMutations.adminUserGlobalSignOut(request -> request.userPoolId(settings.poolId()).username(username));
        } catch (SdkException ex) {
            throw new CognitoFailure(COGNITO_UNAVAILABLE);
        }
    }

    @Override
    public void setTemporaryPassword(String username, String password) {
        try {
            recoveryMutations.adminSetUserPassword(request -> request.userPoolId(settings.poolId()).username(username)
                    .password(password).permanent(false));
        } catch (InvalidPasswordException | PasswordHistoryPolicyViolationException ex) {
            throw new CognitoFailure(INVALID_TEMPORARY_PASSWORD);
        } catch (SdkException ex) {
            throw new CognitoFailure(COGNITO_UNAVAILABLE);
        }
    }

    @Override
    public void removeTotp(String username) {
        try {
            recoveryMutations.adminDeleteSoftwareToken(request -> request.userPoolId(settings.poolId()).username(username));
        } catch (SdkException ex) {
            throw new CognitoFailure(COGNITO_UNAVAILABLE);
        }
    }
}
