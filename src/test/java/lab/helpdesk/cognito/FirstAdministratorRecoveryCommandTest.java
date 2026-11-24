package lab.helpdesk.cognito;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = FirstAdministratorRecoveryCommand.OperatorConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
            "helpdesk.cognito.issuer=https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_LocalProof",
            "helpdesk.cognito.domain=https://local-proof.auth.ap-northeast-2.amazoncognito.com",
            "helpdesk.cognito.app-origin=http://localhost:8080",
            "helpdesk.cognito.client-id=syntheticclient",
            "helpdesk.cognito.bootstrap-subject=synthetic-first-administrator",
            "helpdesk.cognito.first-admin-recovery.operator-role-arn=arn:aws:iam::123456789012:role/helpdesk-recovery"
        })
@ActiveProfiles({"postgres", "cognito", "first-admin-recovery"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FirstAdministratorRecoveryCommandTest {
    @Autowired private FirstAdministratorRecoveryCommand command;
    @Autowired private CognitoRecoveries recoveries;
    @Autowired private ApplicationContext context;
    @MockitoBean(name = "operatorAwsCredentials") private AwsCredentialsProvider credentials;
    @MockitoBean private StsClient sts;
    @MockitoBean private CognitoProvider provider;
    @MockitoBean private DataSource dataSource;

    @Test
    void standaloneConfigurationHasTransactionsAndRejectsAnIamUserBeforeDatabaseAccess() throws Exception {
        assertThat(context.getBeansOfType(CognitoController.class)).isEmpty();
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        assertThat(AopUtils.isAopProxy(recoveries)).isTrue();
        when(sts.getCallerIdentity()).thenReturn(GetCallerIdentityResponse.builder().account("123456789012")
                .arn("arn:aws:iam::123456789012:user/helpdesk-recovery").build());

        assertThatThrownBy(() -> command.execute("status")).hasMessage("FIRST_ADMINISTRATOR_OPERATOR_REQUIRED");
        verify(dataSource, never()).getConnection();
        verifyNoInteractions(provider);
    }

    @Test
    void invalidCommandDoesNotCallAws() {
        assertThatThrownBy(() -> command.execute("prepare", "invalid-id")).hasMessage("INVALID_INPUT");
        assertThatThrownBy(() -> command.execute("reset-password", "invalid-id")).hasMessage("INVALID_INPUT");
        verifyNoInteractions(sts, provider);
    }
}
