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

    private final CognitoAccountAccess access;
    private final JdbcCognitoAccounts accountRepository;
    private final JdbcCognitoRecoveries repository;
    private final CognitoProvider provider;
    private final TransactionTemplate transactions;

    CognitoRecoveries(CognitoAccountAccess access, JdbcCognitoAccounts accountRepository, JdbcCognitoRecoveries repository,
            CognitoProvider provider, PlatformTransactionManager manager) {
        this.access = access;
        this.accountRepository = accountRepository;
        this.repository = repository;
        this.provider = provider;
        this.transactions = new TransactionTemplate(manager);
    }

    Status status(Operator operator, UUID id) {
        return targetForOperator(authorize(operator, false), id).status();
    }

    Status firstAdministratorStatus(AwsOperator operator) {
        var account = access.firstAdministrator(operator.caller(), false);
        return repository.findPendingAwsRequest(account.userId()).map(recovery -> new Target(account, recovery).status())
                .orElseGet(() -> new Status(null, account.accountKey(), RecoveryKind.TOTP_RESET, null, account.status(),
                        account.sessionVersion()));
    }

    @Transactional
    public Status startFirstAdministratorRecovery(AwsOperator operator, UUID requestId, long expectedSessionVersion) {
        if (requestId == null || expectedSessionVersion < 0) throw new CognitoFailure(INVALID_INPUT);
        var account = access.firstAdministrator(operator.caller(), true);
        var old = repository.findAwsRequest(account.userId(), requestId);
        if (old.isPresent()) {
            if (old.get().sessionVersion() - 1 != expectedSessionVersion) {
                throw new CognitoFailure(ACCOUNT_RECOVERY_REQUEST_CONFLICT);
            }
            return new Target(account, old.get()).status();
        }
        if (account.status() == AccountStatus.RECOVERY_PENDING) throw new CognitoFailure(ACCOUNT_RECOVERY_PENDING);
        if (account.sessionVersion() != expectedSessionVersion) throw new CognitoFailure(ACCOUNT_STATUS_VERSION_CONFLICT);
        var opened = accountRepository.startRecovery(account, null, operator.caller().arn(), requestId,
                RecoveryKind.TOTP_RESET, CognitoAccounts.StatusChangeReason.SECURITY_REVIEW.name());
        return target(account, opened.id()).status();
    }

    @Transactional(propagation = Propagation.NEVER)
    public Status prepare(Operator operator, UUID id, String temporaryPassword) {
        var preparation = transactions.execute(tx -> {
            var target = targetForOperator(authorize(operator, true), id);
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
        UUID accountKey = preparation.target().account().accountKey();

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
            if (!mutationStarted || ex instanceof CognitoFailure failure
                    && (failure.code() == INVALID_TEMPORARY_PASSWORD || failure.code() == COGNITO_RECOVERY_PERMISSION_DENIED)) {
                releaseAttempt(accountKey, id, preparation.attempt());
            }
            throw ex;
        }
        return providerReady(accountKey, id, preparation.attempt());
    }

    @Transactional(propagation = Propagation.NEVER)
    public Status recheck(Operator operator, UUID id) {
        var target = targetForOperator(authorize(operator, false), id);
        var recovery = target.recovery();
        if (recovery.providerReadyAt() != null) return target.status();
        target.requirePending();
        if (recovery.providerMutationAt() == null) throw new CognitoFailure(ACCOUNT_RECOVERY_NOT_READY);
        var user = inspectProvider(target);
        boolean applied = recovery.kind() == RecoveryKind.PASSWORD_RESET
                ? user.passwordChangeRequired() && user.totpRegistered()
                : user.confirmed() && !user.totpRegistered();
        if (!applied) throw new CognitoFailure(ACCOUNT_RECOVERY_PROVIDER_UNCONFIRMED);
        return providerReady(target.account().accountKey(), id, recovery.providerAttemptId());
    }

    @Transactional(propagation = Propagation.NEVER)
    public boolean verifyAuthentication(UUID id, CognitoAccounts.Identity identity, Instant authenticatedAt,
            Instant firstAuthenticatedAt) {
        var account = accountRepository.find(identity.issuer(), identity.subject())
                .orElseThrow(() -> new CognitoFailure(FRESH_AUTHENTICATION_REQUIRED));
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
    public Status approve(Operator operator, UUID id) {
        var allowed = authorize(operator, true);
        var target = targetForOperator(allowed, id);
        var recovery = target.recovery();
        if (recovery.completedAt() != null) return target.status();
        target.requirePending();
        if (recovery.proofAuthTime() == null) throw new CognitoFailure(ACCOUNT_RECOVERY_PROOF_REQUIRED);
        accountRepository.restoreAfterRecovery(target.account(), recovery.previousStatus(), recovery.proofAuthTime());
        repository.complete(id, allowed.administratorId(), allowed.awsArn());
        return target(target.account(), id).status();
    }

    private AuthorizedAccount authorize(Operator operator, boolean lock) {
        if (operator instanceof AwsOperator aws) {
            return new AuthorizedAccount(access.firstAdministrator(aws.caller(), lock), null, aws.caller().arn());
        }
        var helpdesk = (HelpdeskOperator) operator;
        long administratorId = access.requireAdministrator(helpdesk.administrator()).userId();
        var account = lock ? access.lockAccount(helpdesk.accountKey())
                : accountRepository.find(helpdesk.accountKey()).orElseThrow(() -> new CognitoFailure(ACCOUNT_NOT_FOUND));
        if (account.accountAdmin()) throw new CognitoFailure(FIRST_ADMINISTRATOR_PROTECTED);
        return new AuthorizedAccount(account, administratorId, null);
    }

    private Target lockTarget(UUID accountKey, UUID id) {
        return target(access.lockAccount(accountKey), id);
    }

    private Target targetForOperator(AuthorizedAccount operator, UUID id) {
        var target = target(operator.account(), id);
        if (target.recovery().awsRecovery() != (operator.awsArn() != null)) {
            throw new CognitoFailure(ACCOUNT_RECOVERY_NOT_FOUND);
        }
        return target;
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

    sealed interface Operator permits HelpdeskOperator, AwsOperator { }
    record HelpdeskOperator(UUID administrator, UUID accountKey) implements Operator { }
    record AwsOperator(AwsRecoveryOperator.Caller caller) implements Operator { }

    private record AuthorizedAccount(JdbcCognitoAccounts.Account account, Long administratorId, String awsArn) { }

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
