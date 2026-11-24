package lab.helpdesk.cognito;

import java.util.regex.Pattern;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sts.StsClient;

import static lab.helpdesk.cognito.CognitoFailure.Code.FIRST_ADMINISTRATOR_OPERATOR_REQUIRED;
import static lab.helpdesk.cognito.CognitoFailure.Code.FIRST_ADMINISTRATOR_RECOVERY_CONFIGURATION_REQUIRED;

final class AwsRecoveryOperator {
    private static final Pattern IAM_ROLE = Pattern.compile("arn:aws:iam::([0-9]{12}):role/(?:[!-~]+/)?([A-Za-z0-9_+=,.@-]{1,64})");
    private final StsClient sts;
    private final CognitoSettings settings;
    private final String expectedAccount;
    private final Pattern expectedSessionArn;

    AwsRecoveryOperator(StsClient sts, CognitoSettings settings, String roleArn) {
        if (roleArn == null || settings.bootstrapSubject() == null) {
            throw new CognitoFailure(FIRST_ADMINISTRATOR_RECOVERY_CONFIGURATION_REQUIRED);
        }
        var role = IAM_ROLE.matcher(roleArn);
        if (!role.matches()) throw new CognitoFailure(FIRST_ADMINISTRATOR_RECOVERY_CONFIGURATION_REQUIRED);
        this.sts = sts;
        this.settings = settings;
        this.expectedAccount = role.group(1);
        String sessionPrefix = "arn:aws:sts::" + expectedAccount + ":assumed-role/" + role.group(2) + "/";
        this.expectedSessionArn = Pattern.compile(Pattern.quote(sessionPrefix) + "[A-Za-z0-9_+=,.@-]{2,64}");
    }

    Caller requireCaller() {
        try {
            var identity = sts.getCallerIdentity();
            String arn = identity.arn();
            if (!expectedAccount.equals(identity.account()) || arn == null || !expectedSessionArn.matcher(arn).matches()) {
                throw new CognitoFailure(FIRST_ADMINISTRATOR_OPERATOR_REQUIRED);
            }
            return new Caller(arn, settings.issuer(), settings.bootstrapSubject());
        } catch (SdkException ex) {
            throw new CognitoFailure(FIRST_ADMINISTRATOR_OPERATOR_REQUIRED);
        }
    }

    static final class Caller {
        private final String arn;
        private final String issuer;
        private final String subject;

        private Caller(String arn, String issuer, String subject) {
            this.arn = arn;
            this.issuer = issuer;
            this.subject = subject;
        }

        String arn() { return arn; }

        boolean matches(JdbcCognitoAccounts.Account account) {
            return account.accountAdmin() && issuer.equals(account.issuer()) && subject.equals(account.subject());
        }

        @Override public String toString() { return "AwsRecoveryCaller[redacted]"; }
    }
}
