package lab.helpdesk.cognito;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lab.helpdesk.identity.UserIdentityRepository;
import lab.helpdesk.support.SupportRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static lab.helpdesk.cognito.CognitoFailure.Code.*;

@Service
@Profile("postgres")
class CognitoAccounts {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration INVITATION_TTL = Duration.ofHours(24);

    private final JdbcCognitoAccounts repository;
    private final CognitoAccountAccess access;
    private final UserIdentityRepository identities;
    private final SupportRepository support;

    CognitoAccounts(JdbcCognitoAccounts repository, CognitoAccountAccess access, UserIdentityRepository identities,
            SupportRepository support) {
        this.repository = repository;
        this.access = access;
        this.identities = identities;
        this.support = support;
    }

    Optional<JdbcCognitoAccounts.Account> find(Identity identity) {
        return repository.find(identity.issuer(), identity.subject());
    }

    Optional<JdbcCognitoAccounts.Account> find(UUID accountKey) {
        return repository.find(accountKey);
    }

    void checkEnrollment(String hash, Identity identity, String bootstrapSubject, Instant now) {
        if (hash != null) {
            requireInvitation(hash, identity, now, false);
        } else {
            checkBootstrap(identity, bootstrapSubject);
        }
    }

    @Transactional
    public IssuedInvitation issue(UUID administrator, String address, String role, Long serviceId,
            String displayName, Instant now) {
        var issuer = access.requireAdministrator(administrator);
        String email = canonicalEmail(address);
        if ("CUSTOMER".equals(role)) {
            if (serviceId != null || displayName != null) throw new CognitoFailure(INVALID_INPUT);
            role = null;
        } else if (("AGENT".equals(role) || "SUPERVISOR".equals(role))
                && serviceId != null && serviceId > 0 && support.service(serviceId).isPresent()
                && displayName != null && !displayName.isBlank() && displayName.length() <= 120) {
            displayName = displayName.strip();
        } else {
            throw new CognitoFailure(INVALID_INPUT);
        }

        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant issuedAt = now.truncatedTo(ChronoUnit.MICROS);
        Instant expiry = issuedAt.plus(INVITATION_TTL);
        repository.boundLockWait();
        try {
            repository.issue(hashToken(token),
                    new JdbcCognitoAccounts.Invitation(email, serviceId, role, displayName, expiry),
                    issuer.userId(), issuedAt);
        } catch (DuplicateKeyException | ConcurrencyFailureException ex) {
            throw new CognitoFailure(INVITATION_UNAVAILABLE);
        }
        return new IssuedInvitation(token, expiry);
    }

    List<AccountSummary> listAccounts(UUID administrator) {
        access.requireAdministrator(administrator);
        return repository.listAccounts();
    }

    @Transactional
    public StatusResult changeStatus(UUID administrator, UUID accountKey, AccountStatus status,
            Long expectedSessionVersion, StatusChangeReason reason) {
        var operator = access.requireAdministrator(administrator);
        if (status == null || status == AccountStatus.RECOVERY_PENDING || expectedSessionVersion == null
                || expectedSessionVersion < 0 || reason == null) {
            throw new CognitoFailure(INVALID_INPUT);
        }
        var target = access.lockAccount(accountKey);
        if (target.sessionVersion() != expectedSessionVersion) {
            throw new CognitoFailure(ACCOUNT_STATUS_VERSION_CONFLICT);
        }
        if (target.status() == AccountStatus.RECOVERY_PENDING) throw new CognitoFailure(ACCOUNT_RECOVERY_PENDING);
        if (target.status() == status) return new StatusResult(accountKey, status, target.sessionVersion());
        if (target.accountAdmin()) throw new CognitoFailure(FIRST_ADMINISTRATOR_PROTECTED);
        long version = repository.changeStatus(target, status, operator.userId(), reason.name());
        return new StatusResult(accountKey, status, version);
    }

    @Transactional
    public RecoveryStart startRecovery(UUID administrator, UUID accountKey, UUID clientRequestId,
            Long expectedSessionVersion, RecoveryKind kind, StatusChangeReason reason) {
        var operator = access.requireAdministrator(administrator);
        if (clientRequestId == null || expectedSessionVersion == null || expectedSessionVersion < 0
                || kind == null || reason == null) {
            throw new CognitoFailure(INVALID_INPUT);
        }
        var target = access.lockAccount(accountKey);
        if (target.accountAdmin()) throw new CognitoFailure(FIRST_ADMINISTRATOR_PROTECTED);

        var recovery = repository.findRecovery(target.userId(), operator.userId(), clientRequestId).orElse(null);
        boolean created = recovery == null;
        if (created) {
            if (target.status() == AccountStatus.RECOVERY_PENDING) throw new CognitoFailure(ACCOUNT_RECOVERY_PENDING);
            if (target.sessionVersion() != expectedSessionVersion) {
                throw new CognitoFailure(ACCOUNT_STATUS_VERSION_CONFLICT);
            }
            recovery = repository.startRecovery(target, operator.userId(), null, clientRequestId, kind, reason.name());
        } else if (recovery.kind() != kind || !recovery.reason().equals(reason.name())
                || recovery.sessionVersion() - 1 != expectedSessionVersion) {
            throw new CognitoFailure(ACCOUNT_RECOVERY_REQUEST_CONFLICT);
        }
        return new RecoveryStart(new RecoveryReceipt(recovery.id(), accountKey, recovery.kind(),
                recovery.sessionVersion(), recovery.requestedAt()), created);
    }

    @Transactional
    public JdbcCognitoAccounts.Account accept(String hash, Identity identity, String bootstrapSubject, Instant now) {
        repository.boundLockWait();
        JdbcCognitoAccounts.Invitation invitation = null;
        if (hash == null) {
            checkBootstrap(identity, bootstrapSubject);
        } else {
            invitation = requireInvitation(hash, identity, now, true);
        }

        UUID key = UUID.randomUUID();
        long id = identities.bind(key, "cognito-" + key);
        if (!repository.bind(id, identity.issuer(), identity.subject(), hash == null)) {
            throw new CognitoFailure(INVITATION_UNAVAILABLE);
        }
        if (invitation != null) {
            if (invitation.serviceId() != null) {
                support.registerMember(invitation.serviceId(), id, invitation.displayName(), invitation.supportRole());
            }
            repository.consume(hash, id, now.truncatedTo(ChronoUnit.MICROS));
        }
        return repository.find(key).orElseThrow(() -> new IllegalStateException("COGNITO_BINDING_MISSING"));
    }

    private JdbcCognitoAccounts.Invitation requireInvitation(String hash, Identity identity, Instant now, boolean lock) {
        return repository.pendingInvitation(hash, lock)
                .filter(invite -> invite.expiresAt().isAfter(now) && invite.email().equals(identity.email()))
                .orElseThrow(() -> new CognitoFailure(INVITATION_UNAVAILABLE));
    }

    private void checkBootstrap(Identity identity, String configuredSubject) {
        if (configuredSubject == null || !configuredSubject.equals(identity.subject())
                || repository.firstAdministratorExists()) {
            throw new CognitoFailure(INVITATION_UNAVAILABLE);
        }
    }

    static String canonicalEmail(String email) {
        if (email == null || email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new CognitoFailure(INVALID_INPUT);
        }
        return email.toLowerCase(Locale.ROOT);
    }

    static String hashToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new CognitoFailure(INVALID_INPUT);
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available");
        }
    }

    record Identity(String issuer, String subject, String email) {
        @Override public String toString() { return "CognitoIdentity[redacted]"; }
    }

    record IssuedInvitation(String token, Instant expiresAt) {
        @Override public String toString() { return "IssuedInvitation[redacted]"; }
    }

    enum StatusChangeReason { OWNER_REQUEST, SERVICE_ACCESS_ENDED, SECURITY_REVIEW, ADMINISTRATIVE_REVIEW }

    record StatusResult(UUID accountKey, AccountStatus status, long sessionVersion) { }

    record RecoveryReceipt(UUID recoveryId, UUID accountKey, RecoveryKind kind, long sessionVersion,
            Instant requestedAt) { }

    record RecoveryStart(RecoveryReceipt receipt, boolean created) { }
}
