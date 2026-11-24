package lab.helpdesk.cognito;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.sts.StsClient;

import static lab.helpdesk.cognito.CognitoFailure.Code.*;

public final class FirstAdministratorRecoveryCommand {
    private static final String COMMAND = "first-admin-recovery";
    private static final String OPERATOR_ROLE = "helpdesk.cognito.first-admin-recovery.operator-role-arn";
    private final AwsRecoveryOperator operator;
    private final CognitoRecoveries recoveries;

    FirstAdministratorRecoveryCommand(AwsRecoveryOperator operator, CognitoRecoveries recoveries) {
        this.operator = operator;
        this.recoveries = recoveries;
    }

    public static boolean isRequested(String[] args) {
        return args.length > 0 && COMMAND.equals(args[0]);
    }

    public static int run(String[] args) {
        try {
            var input = Input.parse(Arrays.copyOfRange(args, 1, args.length));
            // Boot startup diagnostics can include raw configuration values.
            try (var context = new SpringApplicationBuilder(OperatorConfiguration.class)
                    .profiles("postgres", "cognito", COMMAND).web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF).logStartupInfo(false).run("--logging.level.root=OFF")) {
                var result = context.getBean(FirstAdministratorRecoveryCommand.class).execute(input);
                System.out.println("accountStatus=" + result.accountStatus() + " sessionVersion=" + result.sessionVersion());
                if (result.recoveryId() != null) {
                    System.out.println("recoveryId=" + result.recoveryId() + " phase=" + result.phase());
                }
                return 0;
            }
        } catch (RuntimeException failure) {
            if (NestedExceptionUtils.getMostSpecificCause(failure) instanceof CognitoFailure rejected) {
                System.err.println(rejected.code().name());
                return rejected.code() == INVALID_INPUT ? 2 : 1;
            }
            System.err.println("FIRST_ADMINISTRATOR_RECOVERY_UNAVAILABLE");
            return 1;
        }
    }

    CognitoRecoveries.Status execute(String... args) {
        return execute(Input.parse(args));
    }

    private CognitoRecoveries.Status execute(Input input) {
        var caller = new CognitoRecoveries.AwsOperator(operator.requireCaller());
        return switch (input.operation()) {
            case "start" -> recoveries.startFirstAdministratorRecovery(caller, input.id(), input.version());
            case "status" -> input.id() == null
                    ? recoveries.firstAdministratorStatus(caller) : recoveries.status(caller, input.id());
            case "prepare" -> recoveries.prepare(caller, input.id(), null);
            case "recheck" -> recoveries.recheck(caller, input.id());
            case "approve" -> recoveries.approve(caller, input.id());
            default -> throw new CognitoFailure(INVALID_INPUT);
        };
    }

    private record Input(String operation, UUID id, long version) {
        static Input parse(String[] args) {
            if (args.length == 1 && "status".equals(args[0])) return new Input("status", null, 0);
            try {
                if (args.length == 3 && "start".equals(args[0])) {
                    long version = Long.parseLong(args[2]);
                    if (version >= 0) return new Input("start", UUID.fromString(args[1]), version);
                }
                if (args.length == 2 && switch (args[0]) {
                    case "status", "prepare", "recheck", "approve" -> true;
                    default -> false;
                }) {
                    return new Input(args[0], UUID.fromString(args[1]), 0);
                }
            } catch (IllegalArgumentException ignored) {
                // Operator input can contain sensitive values. Report the usage error without echoing it.
            }
            throw new CognitoFailure(INVALID_INPUT);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile(COMMAND)
    @ConditionalOnNotWebApplication
    @EnableTransactionManagement
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class})
    @Import({JdbcCognitoAccounts.class, JdbcCognitoRecoveries.class, CognitoAccountAccess.class, CognitoRecoveries.class})
    static class OperatorConfiguration {
        @Bean
        CognitoSettings operatorCognitoSettings(Environment environment) {
            return CognitoSettings.read(environment);
        }

        @Bean
        AwsCredentialsProvider operatorAwsCredentials() {
            try (var source = DefaultCredentialsProvider.builder().build()) {
                if (source.resolveCredentials() instanceof AwsSessionCredentials session) {
                    return StaticCredentialsProvider.create(session);
                }
            } catch (SdkException ignored) {
                // Credential errors can contain local settings. Report only the operator error code.
            }
            throw new CognitoFailure(FIRST_ADMINISTRATOR_OPERATOR_REQUIRED);
        }

        @Bean(destroyMethod = "close")
        StsClient operatorSts(AwsCredentialsProvider credentials) {
            return StsClient.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(credentials)
                    .endpointOverride(URI.create("https://sts.ap-northeast-2.amazonaws.com"))
                    .overrideConfiguration(builder -> builder.apiCallTimeout(Duration.ofSeconds(8)))
                    .build();
        }

        @Bean(destroyMethod = "close")
        CognitoIdentityProviderClient operatorCognitoReads(AwsCredentialsProvider credentials) {
            return operatorClient(credentials, false);
        }

        @Bean(destroyMethod = "close")
        CognitoIdentityProviderClient operatorCognitoMutations(AwsCredentialsProvider credentials) {
            return operatorClient(credentials, true);
        }

        private static CognitoIdentityProviderClient operatorClient(AwsCredentialsProvider credentials, boolean mutation) {
            return AwsCognitoProvider.clientBuilder(mutation).credentialsProvider(credentials)
                    .endpointOverride(URI.create("https://cognito-idp.ap-northeast-2.amazonaws.com")).build();
        }

        @Bean
        CognitoProvider operatorCognitoProvider(@Qualifier("operatorCognitoReads") CognitoIdentityProviderClient reads,
                @Qualifier("operatorCognitoMutations") CognitoIdentityProviderClient mutations, CognitoSettings settings) {
            return new AwsCognitoProvider(reads, mutations, settings);
        }

        @Bean
        FirstAdministratorRecoveryCommand recoveryCommand(StsClient sts, CognitoSettings settings,
                CognitoRecoveries recoveries, Environment environment) {
            var operator = new AwsRecoveryOperator(sts, settings, environment.getProperty(OPERATOR_ROLE));
            return new FirstAdministratorRecoveryCommand(operator, recoveries);
        }
    }
}
