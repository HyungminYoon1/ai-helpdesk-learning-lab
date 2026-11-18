package lab.helpdesk.cognito;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static lab.helpdesk.cognito.CognitoFailure.Code.*;

@Service
@Profile("cognito")
class CognitoRecoveries {
    private static final Duration PREPARATION_WINDOW = Duration.ofMinutes(1);

    private final CognitoAccounts accounts;
    private final JdbcCognitoAccounts accountRepository;
    private final JdbcCognitoRecoveries repository;
    private final CognitoProvider provider;
    private final TransactionTemplate transactions;

    CognitoRecoveries(CognitoAccounts accounts, JdbcCognitoAccounts accountRepository, JdbcCognitoRecoveries repository,
            CognitoProvider provider, PlatformTransactionManager manager) {
        this.accounts = accounts;
        this.accountRepository = accountRepository;
        this.repository = repository;
        this.provider = provider;
        this.transactions = new TransactionTemplate(manager);
    }

    Status status(UUID administrator, UUID accountKey, UUID id) {
        return readForAdministrator(administrator, accountKey, id).status();
    }

    @Transactional(propagation = Propagation.NEVER)
    public Status prepare(UUID administrator, UUID accountKey, UUID id, String temporaryPassword) {
        var preparation = transactions.execute(tx -> {
            accounts.requireAdministrator(administrator);
            var target = lockTarget(accountKey, id);
            if (target.recovery().providerReadyAt() != null) return new Preparation(target, null);
            target.requirePending();
            validatePassword(target.recovery().kind(), temporaryPassword);
            if (target.recovery().providerMutationAt() != null) {
                throw new CognitoFailure(ACCOUNT_RECOVERY_PROVIDER_UNCONFIRMED);
            }
            Instant startedAt = target.recovery().providerStartedAt();
            if (startedAt != null && startedAt.plus(PREPARATION_WINDOW).isAfter(Instant.now())) {
                throw new CognitoFailure(ACCOUNT_RECOVERY_IN_PROGRESS);
            }
            UUID attempt = UUID.randomUUID();
            repository.claim(id, attempt);
            return new Preparation(target, attempt);
        });
        if (preparation.attempt() == null) return preparation.target().status();

        boolean mutationStarted = false;
        try {
            var user = inspectProvider(preparation.target());
            if (!user.confirmed() || !user.totpRegistered()) throw new CognitoFailure(ACCOUNT_RECOVERY_NOT_READY);
            provider.signOutEverywhere(user.username());
            transactions.executeWithoutResult(tx -> {
                var target = lockTarget(accountKey, id);
                target.requireAttempt(preparation.attempt());
                repository.markMutation(id);
            });
            mutationStarted = true;
            if (preparation.target().recovery().kind() == RecoveryKind.PASSWORD_RESET) {
                provider.setTemporaryPassword(user.username(), temporaryPassword);
            } else {
                provider.removeTotp(user.username());
            }
        } catch (RuntimeException ex) {
            if (!mutationStarted || ex instanceof CognitoFailure failure && failure.code() == INVALID_TEMPORARY_PASSWORD) {
                releaseAttempt(accountKey, id, preparation.attempt());
            }
            throw ex;
        }
        return providerReady(accountKey, id, preparation.attempt());
    }

    @Transactional(propagation = Propagation.NEVER)
    public Status recheck(UUID administrator, UUID accountKey, UUID id) {
        var target = readForAdministrator(administrator, accountKey, id);
        var recovery = target.recovery();
        if (recovery.providerReadyAt() != null) return target.status();
        target.requirePending();
        if (recovery.providerMutationAt() == null) throw new CognitoFailure(ACCOUNT_RECOVERY_NOT_READY);
        var user = inspectProvider(target);
        boolean applied = recovery.kind() == RecoveryKind.PASSWORD_RESET
                ? user.passwordChangeRequired() && user.totpRegistered()
                : user.confirmed() && !user.totpRegistered();
        if (!applied) throw new CognitoFailure(ACCOUNT_RECOVERY_PROVIDER_UNCONFIRMED);
        return providerReady(accountKey, id, recovery.providerAttemptId());
    }

    @Transactional(propagation = Propagation.NEVER)
    public boolean verifyAuthentication(UUID id, CognitoAccounts.Identity identity, Instant authenticatedAt,
            Instant firstAuthenticatedAt) {
        var account = accounts.find(identity).orElseThrow(() -> new CognitoFailure(FRESH_AUTHENTICATION_REQUIRED));
        var target = target(account, id);
        target.requireFreshAuthentication(authenticatedAt);
        if (firstAuthenticatedAt != null && !authenticatedAt.isAfter(firstAuthenticatedAt)) {
            throw new CognitoFailure(FRESH_AUTHENTICATION_REQUIRED);
        }
        var user = inspectProvider(target);
        if (!user.confirmed() || !user.totpRegistered()) throw new CognitoFailure(ACCOUNT_RECOVERY_NOT_READY);
        if (target.recovery().kind() == RecoveryKind.TOTP_RESET && firstAuthenticatedAt == null) return false;

        transactions.executeWithoutResult(tx -> {
            var current = lockTarget(account.accountKey(), id);
            current.requireFreshAuthentication(authenticatedAt);
            repository.recordProof(id, authenticatedAt);
        });
        return true;
    }

    @Transactional
    public Status approve(UUID administrator, UUID accountKey, UUID id) {
        long administratorId = accounts.requireAdministrator(administrator).userId();
        var target = lockTarget(accountKey, id);
        var recovery = target.recovery();
        if (recovery.completedAt() != null) return target.status();
        target.requirePending();
        if (recovery.proofAuthTime() == null) throw new CognitoFailure(ACCOUNT_RECOVERY_PROOF_REQUIRED);
        accountRepository.restoreAfterRecovery(target.account(), recovery.previousStatus(), recovery.proofAuthTime());
        repository.complete(id, administratorId);
        return target(target.account(), id).status();
    }

    private Target readForAdministrator(UUID administrator, UUID accountKey, UUID id) {
        accounts.requireAdministrator(administrator);
        return target(accounts.find(accountKey).orElseThrow(() -> new CognitoFailure(ACCOUNT_NOT_FOUND)), id);
    }

    private Target lockTarget(UUID accountKey, UUID id) {
        return target(accounts.lockTargetAccount(accountKey), id);
    }

    private Target target(JdbcCognitoAccounts.Account account, UUID id) {
        var recovery = repository.find(id).filter(row -> row.targetUserId() == account.userId())
                .orElseThrow(() -> new CognitoFailure(ACCOUNT_RECOVERY_NOT_FOUND));
        return new Target(account, recovery);
    }

    private CognitoProvider.RecoveryUser inspectProvider(Target target) {
        provider.requireTotpOnly();
        if (target.recovery().kind() == RecoveryKind.PASSWORD_RESET) provider.requireAdminOnlyRecovery();
        return provider.recoveryUser(target.account().issuer(), target.account().subject());
    }

    private Status providerReady(UUID accountKey, UUID id, UUID attempt) {
        return transactions.execute(tx -> {
            var target = lockTarget(accountKey, id);
            if (target.recovery().providerReadyAt() != null) return target.status();
            target.requireAttempt(attempt);
            repository.markReady(id);
            return target(target.account(), id).status();
        });
    }

    private void releaseAttempt(UUID accountKey, UUID id, UUID attempt) {
        transactions.executeWithoutResult(tx -> {
            var target = lockTarget(accountKey, id);
            if (target.recovery().providerReadyAt() == null && attempt.equals(target.recovery().providerAttemptId())) {
                repository.release(id);
            }
        });
    }

    private static void validatePassword(RecoveryKind kind, String password) {
        if (kind == RecoveryKind.PASSWORD_RESET) {
            if (password == null || password.isBlank() || password.length() > 256) {
                throw new CognitoFailure(INVALID_TEMPORARY_PASSWORD);
            }
        } else if (password != null) {
            throw new CognitoFailure(INVALID_INPUT);
        }
    }

    enum Phase { REQUESTED, PREPARING, PROVIDER_RESULT_UNCONFIRMED, AWAITING_AUTHENTICATION, AUTHENTICATION_VERIFIED, COMPLETED }

    record Status(UUID recoveryId, UUID accountKey, RecoveryKind kind, Phase phase, AccountStatus accountStatus,
            long sessionVersion) { }

    private record Preparation(Target target, UUID attempt) { }

    private record Target(JdbcCognitoAccounts.Account account, JdbcCognitoRecoveries.Recovery recovery) {
        void requirePending() {
            if (recovery.completedAt() != null || account.status() != AccountStatus.RECOVERY_PENDING
                    || account.sessionVersion() != recovery.sessionVersion()) {
                throw new CognitoFailure(ACCOUNT_RECOVERY_NOT_READY);
            }
        }

        void requireAttempt(UUID attempt) {
            requirePending();
            if (!attempt.equals(recovery.providerAttemptId())) throw new CognitoFailure(ACCOUNT_RECOVERY_IN_PROGRESS);
        }

        void requireFreshAuthentication(Instant authenticatedAt) {
            requirePending();
            if (recovery.providerReadyAt() == null) throw new CognitoFailure(ACCOUNT_RECOVERY_NOT_READY);
            if (!authenticatedAt.isAfter(recovery.providerReadyAt())) throw new CognitoFailure(FRESH_AUTHENTICATION_REQUIRED);
        }

        Status status() {
            boolean completed = recovery.completedAt() != null;
            return new Status(recovery.id(), account.accountKey(), recovery.kind(), phase(),
                    completed ? recovery.previousStatus() : AccountStatus.RECOVERY_PENDING,
                    recovery.sessionVersion() + (completed ? 1 : 0));
        }

        private Phase phase() {
            if (recovery.completedAt() != null) return Phase.COMPLETED;
            if (recovery.proofAuthTime() != null) return Phase.AUTHENTICATION_VERIFIED;
            if (recovery.providerReadyAt() != null) return Phase.AWAITING_AUTHENTICATION;
            if (recovery.providerMutationAt() != null) return Phase.PROVIDER_RESULT_UNCONFIRMED;
            return recovery.providerStartedAt() == null ? Phase.REQUESTED : Phase.PREPARING;
        }
    }
}
