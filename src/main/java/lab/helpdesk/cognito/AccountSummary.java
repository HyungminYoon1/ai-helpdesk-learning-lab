package lab.helpdesk.cognito;

import java.util.UUID;

record AccountSummary(UUID accountKey, String invitedEmail, boolean accountAdmin, AccountStatus status,
        long sessionVersion) {
    @Override public String toString() { return "CognitoAccountSummary[redacted]"; }
}
