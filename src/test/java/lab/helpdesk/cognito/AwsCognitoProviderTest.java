package lab.helpdesk.cognito;

import java.net.URI;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DescribeUserPoolClientRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DescribeUserPoolClientResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserPoolMfaConfigRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserPoolMfaConfigResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.OAuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolMfaType;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AwsCognitoProviderTest {
    private final CognitoIdentityProviderClient client = mock(CognitoIdentityProviderClient.class);
    private final CognitoSettings settings = new CognitoSettings(
            "https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_Synthetic",
            URI.create("https://synthetic.auth.ap-northeast-2.amazoncognito.com"), URI.create("http://127.0.0.1:8080"),
            "syntheticclient", null, null);
    private final AwsCognitoProvider provider = new AwsCognitoProvider(client, settings);

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
