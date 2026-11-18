package lab.helpdesk.cognito;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("cognito")
class JdbcCognitoRecoveries {
    private final JdbcTemplate jdbc;

    JdbcCognitoRecoveries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Recovery> find(UUID id) {
        return jdbc.query("""
                SELECT id, target_user_id, previous_status, session_version, kind, provider_attempt_id,
                       provider_started_at, provider_mutation_at, provider_ready_at, proof_auth_time, completed_at
                FROM helpdesk_account_recoveries WHERE id = ?
                """, (row, index) -> new Recovery(row.getObject("id", UUID.class), row.getLong("target_user_id"),
                        AccountStatus.valueOf(row.getString("previous_status")), row.getLong("session_version"),
                        RecoveryKind.valueOf(row.getString("kind")), row.getObject("provider_attempt_id", UUID.class),
                        instant(row, "provider_started_at"), instant(row, "provider_mutation_at"),
                        instant(row, "provider_ready_at"), instant(row, "proof_auth_time"), instant(row, "completed_at")),
                id).stream().findFirst();
    }

    void claim(UUID id, UUID attempt) {
        jdbc.update("""
                UPDATE helpdesk_account_recoveries SET provider_attempt_id = ?, provider_started_at = statement_timestamp()
                WHERE id = ?
                """, attempt, id);
    }

    void markMutation(UUID id) {
        jdbc.update("""
                UPDATE helpdesk_account_recoveries SET provider_mutation_at = statement_timestamp() WHERE id = ?
                """, id);
    }

    void release(UUID id) {
        jdbc.update("""
                UPDATE helpdesk_account_recoveries
                SET provider_attempt_id = NULL, provider_started_at = NULL, provider_mutation_at = NULL WHERE id = ?
                """, id);
    }

    void markReady(UUID id) {
        jdbc.update("""
                UPDATE helpdesk_account_recoveries SET provider_ready_at = statement_timestamp() WHERE id = ?
                """, id);
    }

    void recordProof(UUID id, Instant authenticatedAt) {
        jdbc.update("""
                UPDATE helpdesk_account_recoveries SET proof_auth_time = greatest(proof_auth_time, ?) WHERE id = ?
                """, authenticatedAt.atOffset(ZoneOffset.UTC), id);
    }

    void complete(UUID id, long administratorId) {
        jdbc.update("""
                UPDATE helpdesk_account_recoveries SET completed_at = statement_timestamp(), approved_by = ? WHERE id = ?
                """, administratorId, id);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        var value = row.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record Recovery(UUID id, long targetUserId, AccountStatus previousStatus, long sessionVersion, RecoveryKind kind,
            UUID providerAttemptId, Instant providerStartedAt, Instant providerMutationAt, Instant providerReadyAt,
            Instant proofAuthTime, Instant completedAt) { }
}
