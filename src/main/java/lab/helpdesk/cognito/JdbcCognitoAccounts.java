package lab.helpdesk.cognito;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
class JdbcCognitoAccounts {
    private static final String ACCOUNT_SELECT = """
            SELECT a.user_id, i.account_key, i.login_username, a.first_administrator,
                   a.status, a.session_version, a.reactivated_at,
                   EXISTS (SELECT 1 FROM support_service_memberships m
                           WHERE m.user_id = a.user_id AND m.active) AS agent
            FROM helpdesk_cognito_accounts a
            JOIN helpdesk_user_identities i ON i.id = a.user_id
            """;

    private final JdbcTemplate jdbc;

    JdbcCognitoAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Account> find(String issuer, String subject) {
        return jdbc.query(ACCOUNT_SELECT + " WHERE a.issuer = ? AND a.subject = ?",
                JdbcCognitoAccounts::account, issuer, subject).stream().findFirst();
    }

    Optional<Account> find(UUID accountKey) {
        return jdbc.query(ACCOUNT_SELECT + " WHERE i.account_key = ?",
                JdbcCognitoAccounts::account, accountKey).stream().findFirst();
    }

    boolean firstAdministratorExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM helpdesk_cognito_accounts WHERE first_administrator)
                """, Boolean.class));
    }

    boolean bind(long userId, String issuer, String subject, boolean firstAdministrator) {
        return jdbc.update("""
                INSERT INTO helpdesk_cognito_accounts (user_id, issuer, subject, first_administrator)
                VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, userId, issuer, subject, firstAdministrator) == 1;
    }

    Optional<Invitation> pendingInvitation(String hash, boolean lock) {
        String query = """
                SELECT email, service_id, support_role, display_name, expires_at
                FROM helpdesk_account_invitations
                WHERE token_hash = ? AND accepted_at IS NULL AND revoked_at IS NULL
                """;
        if (lock) query += " FOR UPDATE";
        return jdbc.query(query, (row, index) -> new Invitation(
                row.getString("email"), row.getObject("service_id", Long.class),
                row.getString("support_role"), row.getString("display_name"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant()), hash).stream().findFirst();
    }

    void issue(String hash, Invitation invite, long issuerId, Instant issuedAt) {
        jdbc.update("""
                UPDATE helpdesk_account_invitations SET revoked_at = ?
                WHERE email = ? AND accepted_at IS NULL AND revoked_at IS NULL
                """, utc(issuedAt), invite.email());
        jdbc.update("""
                INSERT INTO helpdesk_account_invitations
                    (token_hash, email, service_id, support_role, display_name, issued_by, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, hash, invite.email(), invite.serviceId(), invite.supportRole(), invite.displayName(),
                issuerId, utc(issuedAt), utc(invite.expiresAt()));
    }

    void consume(String hash, long userId, Instant acceptedAt) {
        if (jdbc.update("""
                UPDATE helpdesk_account_invitations SET accepted_by = ?, accepted_at = ?
                WHERE token_hash = ? AND revoked_at IS NULL AND accepted_at IS NULL
                """, userId, utc(acceptedAt), hash) != 1) {
            throw new CognitoFailure(CognitoFailure.Code.INVITATION_UNAVAILABLE);
        }
    }

    void boundLockWait() {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
    }

    private static Account account(ResultSet row, int index) throws SQLException {
        var reactivatedAt = row.getObject("reactivated_at", OffsetDateTime.class);
        return new Account(row.getLong("user_id"), row.getObject("account_key", UUID.class),
                row.getString("login_username"), row.getBoolean("first_administrator"), row.getBoolean("agent"),
                AccountStatus.valueOf(row.getString("status")), row.getLong("session_version"),
                reactivatedAt == null ? null : reactivatedAt.toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return Objects.requireNonNull(instant).atOffset(ZoneOffset.UTC);
    }

    record Account(long userId, UUID accountKey, String username, boolean accountAdmin, boolean agent,
            AccountStatus status, long sessionVersion, Instant reactivatedAt) {
        @Override public String toString() { return "CognitoAccount[redacted]"; }
    }

    record Invitation(String email, Long serviceId, String supportRole, String displayName, Instant expiresAt) {
        @Override public String toString() { return "CognitoInvitation[redacted]"; }
    }
}
