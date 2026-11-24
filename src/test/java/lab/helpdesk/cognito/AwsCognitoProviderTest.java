package lab.helpdesk.cognito;

import java.net.URI;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteSoftwareTokenRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUserGlobalSignOutRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DescribeUserPoolClientRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DescribeUserPoolClientResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DescribeUserPoolRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DescribeUserPoolResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserPoolMfaConfigRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserPoolMfaConfigResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.OAuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RecoveryOptionNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RecoveryOptionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolMfaType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserStatusType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AwsCognitoProviderTest {
    private final CognitoIdentityProviderClient client = mock(CognitoIdentityProviderClient.class);
    private final CognitoSettings settings = new CognitoSettings(
            "https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_Synthetic",
            URI.create("https://synthetic.auth.ap-northeast-2.amazoncognito.com"), URI.create("http://127.0.0.1:8080"),
            "syntheticclient", null, null);
    private final CognitoIdentityProviderClient mutations = mock(CognitoIdentityProviderClient.class);
    private final AwsCognitoProvider provider = new AwsCognitoProvider(client, mutations, settings);

    @Test
    void acceptsRequiredTotpAndOnlyTheLocalAuthorizationCodeClient() {
        pool(GetUserPoolMfaConfigResponse.builder().mfaConfiguration(UserPoolMfaType.ON)
                .softwareTokenMfaConfiguration(totp -> totp.enabled(true)).build());
        app(List.of("COGNITO"), OAuthFlowType.CODE);

        assertThatCode(provider::requireTotpOnly).doesNotThrowAnyException();
    }

    @Test
    void optionalMfaAndAnAlternativeMfaFactorAreRejected() {
        pool(GetUserPoolMfaConfigResponse.builder().mfaConfiguration(UserPoolMfaType.OPTIONAL)
                .softwareTokenMfaConfiguration(totp -> totp.enabled(true)).build());
        assertThatThrownBy(provider::requireTotpOnly).hasMessage("COGNITO_UNAVAILABLE");

        pool(GetUserPoolMfaConfigResponse.builder().mfaConfiguration(UserPoolMfaType.ON)
                .softwareTokenMfaConfiguration(totp -> totp.enabled(true))
                .emailMfaConfiguration(email -> email.subject("Synthetic")).build());
        assertThatThrownBy(provider::requireTotpOnly).hasMessage("COGNITO_UNAVAILABLE");
    }

    @Test
    void federationAndImplicitTokensAreRejected() {
        pool(GetUserPoolMfaConfigResponse.builder().mfaConfiguration(UserPoolMfaType.ON)
                .softwareTokenMfaConfiguration(totp -> totp.enabled(true)).build());
        app(List.of("COGNITO", "Google"), OAuthFlowType.CODE);
        assertThatThrownBy(provider::requireTotpOnly).hasMessage("COGNITO_UNAVAILABLE");

        app(List.of("COGNITO"), OAuthFlowType.IMPLICIT);
        assertThatThrownBy(provider::requireTotpOnly).hasMessage("COGNITO_UNAVAILABLE");
    }

    @Test
    void recoveryLookupUsesTheBoundSubjectAndChecksTheReturnedIdentity() {
        when(client.adminGetUser(ArgumentMatchers.<Consumer<AdminGetUserRequest.Builder>>any()))
                .thenReturn(recoveryUser("synthetic-sub"), recoveryUser("unrelated-sub"));
        var user = provider.recoveryUser(settings.issuer(), "synthetic-sub");
        assertThat(user.username()).isEqualTo("canonical-cognito-user");
        assertThat(user.passwordChangeRequired()).isTrue();
        assertThat(user.totpRegistered()).isTrue();
        ArgumentCaptor<Consumer<AdminGetUserRequest.Builder>> lookup = ArgumentCaptor.captor();
        verify(client).adminGetUser(lookup.capture());
        var request = AdminGetUserRequest.builder();
        lookup.getValue().accept(request);
        assertThat(request.build().username()).isEqualTo("synthetic-sub");
        assertThat(request.build().userPoolId()).isEqualTo(settings.poolId());
        assertThatThrownBy(() -> provider.recoveryUser(settings.issuer(), "synthetic-sub"))
                .hasMessage("COGNITO_UNAVAILABLE");
        verifyNoInteractions(mutations);
    }

    @Test
    void passwordRecoveryRequiresAdminOnly() {
        when(client.describeUserPool(ArgumentMatchers.<Consumer<DescribeUserPoolRequest.Builder>>any()))
                .thenReturn(poolRecoveryMechanism(RecoveryOptionNameType.ADMIN_ONLY),
                        poolRecoveryMechanism(RecoveryOptionNameType.VERIFIED_EMAIL));
        assertThatCode(provider::requireAdminOnlyRecovery).doesNotThrowAnyException();
        assertThatThrownBy(provider::requireAdminOnlyRecovery).hasMessage("COGNITO_UNAVAILABLE");
    }

    @Test
    void restorationMutationsUseTheRecoveryClientAndARequiredPasswordChange() {
        String username = "canonical-cognito-user";
        provider.signOutEverywhere(username);
        provider.setTemporaryPassword(username, "Synthetic-only-42!");
        provider.removeTotp(username);

        ArgumentCaptor<Consumer<AdminUserGlobalSignOutRequest.Builder>> signOut = ArgumentCaptor.captor();
        ArgumentCaptor<Consumer<AdminSetUserPasswordRequest.Builder>> password = ArgumentCaptor.captor();
        ArgumentCaptor<Consumer<AdminDeleteSoftwareTokenRequest.Builder>> totp = ArgumentCaptor.captor();
        verify(mutations).adminUserGlobalSignOut(signOut.capture());
        verify(mutations).adminSetUserPassword(password.capture());
        verify(mutations).adminDeleteSoftwareToken(totp.capture());
        var signOutRequest = AdminUserGlobalSignOutRequest.builder();
        var passwordRequest = AdminSetUserPasswordRequest.builder();
        var totpRequest = AdminDeleteSoftwareTokenRequest.builder();
        signOut.getValue().accept(signOutRequest);
        password.getValue().accept(passwordRequest);
        totp.getValue().accept(totpRequest);
        assertThat(signOutRequest.build().username()).isEqualTo(username);
        assertThat(passwordRequest.build().username()).isEqualTo(username);
        assertThat(passwordRequest.build().permanent()).isFalse();
        assertThat(totpRequest.build().username()).isEqualTo(username);
        assertThat(List.of(signOutRequest.build().userPoolId(), passwordRequest.build().userPoolId(),
                totpRequest.build().userPoolId())).containsOnly(settings.poolId());
        verifyNoInteractions(client);
    }

    @Test
    void aDefinitivePasswordRejectionReturnsASanitizedValidationError() {
        when(mutations.adminSetUserPassword(ArgumentMatchers.<Consumer<AdminSetUserPasswordRequest.Builder>>any()))
                .thenThrow(InvalidPasswordException.builder().message("synthetic provider rejection").build());
        assertThatThrownBy(() -> provider.setTemporaryPassword("canonical-user", "Synthetic-only-42!"))
                .hasMessage("INVALID_TEMPORARY_PASSWORD");
    }

    @Test
    void totpDeletionDistinguishesAProviderPermissionDenialFromAnUnconfirmedResult() {
        var iamDenial = CognitoIdentityProviderException.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDeniedException").build())
                .message("synthetic permission failure detail").build();
        when(mutations.adminDeleteSoftwareToken(ArgumentMatchers.<Consumer<AdminDeleteSoftwareTokenRequest.Builder>>any()))
                .thenThrow(NotAuthorizedException.builder().statusCode(400).message("synthetic provider detail").build(),
                        iamDenial, SdkClientException.create("synthetic response loss"));

        assertThatThrownBy(() -> provider.removeTotp("canonical-user")).hasMessage("COGNITO_RECOVERY_PERMISSION_DENIED");
        assertThatThrownBy(() -> provider.removeTotp("canonical-user")).hasMessage("COGNITO_RECOVERY_PERMISSION_DENIED");
        assertThatThrownBy(() -> provider.removeTotp("canonical-user")).hasMessage("COGNITO_UNAVAILABLE");
    }

    private static AdminGetUserResponse recoveryUser(String subject) {
        return AdminGetUserResponse.builder().username("canonical-cognito-user").enabled(true)
                .userStatus(UserStatusType.FORCE_CHANGE_PASSWORD).userMFASettingList("SOFTWARE_TOKEN_MFA")
                .userAttributes(AttributeType.builder().name("sub").value(subject).build()).build();
    }

    private static DescribeUserPoolResponse poolRecoveryMechanism(RecoveryOptionNameType kind) {
        return DescribeUserPoolResponse.builder().userPool(pool -> pool.accountRecoverySetting(recovery -> recovery
                .recoveryMechanisms(RecoveryOptionType.builder().name(kind).priority(1).build()))).build();
    }

    private void pool(GetUserPoolMfaConfigResponse response) {
        when(client.getUserPoolMfaConfig(
                ArgumentMatchers.<Consumer<GetUserPoolMfaConfigRequest.Builder>>any())).thenReturn(response);
    }

    private void app(List<String> providers, OAuthFlowType grant) {
        when(client.describeUserPoolClient(
                ArgumentMatchers.<Consumer<DescribeUserPoolClientRequest.Builder>>any()))
                .thenReturn(DescribeUserPoolClientResponse.builder().userPoolClient(app -> app
                        .supportedIdentityProviders(providers).allowedOAuthFlowsUserPoolClient(true)
                        .allowedOAuthFlows(grant)).build());
    }
}
