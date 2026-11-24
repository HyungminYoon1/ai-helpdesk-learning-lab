package lab.helpdesk.cognito;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import static lab.helpdesk.cognito.CognitoFailure.Code.*;

@Component
@Profile("postgres")
final class CognitoAccountAccess {
    private final JdbcCognitoAccounts repository;

    CognitoAccountAccess(JdbcCognitoAccounts repository) {
        this.repository = repository;
    }

    JdbcCognitoAccounts.Account requireAdministrator(UUID administrator) {
        return repository.find(administrator)
                .filter(account -> account.accountAdmin() && account.status() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new CognitoFailure(ACCOUNT_ADMIN_REQUIRED));
    }

    JdbcCognitoAccounts.Account lockAccount(UUID accountKey) {
        repository.boundLockWait();
        return repository.lockForStatusChange(accountKey).orElseThrow(() -> new CognitoFailure(ACCOUNT_NOT_FOUND));
    }

    JdbcCognitoAccounts.Account firstAdministrator(AwsRecoveryOperator.Caller caller, boolean lock) {
        if (lock) repository.boundLockWait();
        return repository.firstAdministrator(lock).filter(caller::matches)
                .orElseThrow(() -> new CognitoFailure(FIRST_ADMINISTRATOR_TARGET_MISMATCH));
    }
}
