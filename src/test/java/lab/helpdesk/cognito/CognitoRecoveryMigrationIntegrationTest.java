package lab.helpdesk.cognito;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class CognitoRecoveryMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v20PreservesV19RecoveriesAndRequiresANonblankAwsApprover() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("19").load().migrate();
        jdbc.update("""
                INSERT INTO helpdesk_user_identities (id, account_key, login_username) VALUES
                    (1, gen_random_uuid(), 'synthetic-first-admin'),
                    (2, gen_random_uuid(), 'synthetic-completed-customer'),
                    (3, gen_random_uuid(), 'synthetic-pending-customer')
                """);
        jdbc.update("""
                INSERT INTO helpdesk_cognito_accounts
                    (user_id, issuer, subject, first_administrator, status, session_version, reactivated_at)
                VALUES (1, 'synthetic-issuer', 'first-admin', TRUE, 'ACTIVE', 0, NULL),
                    (2, 'synthetic-issuer', 'completed-customer', FALSE, 'ACTIVE', 2, TIMESTAMPTZ '2026-11-18 00:01:03+00'),
                    (3, 'synthetic-issuer', 'pending-customer', FALSE, 'RECOVERY_PENDING', 1, NULL)
                """);
        jdbc.update("""
                INSERT INTO helpdesk_account_recoveries
                    (id, target_user_id, administrator_user_id, client_request_id, previous_status, session_version,
                     kind, reason, requested_at, provider_attempt_id, provider_started_at, provider_mutation_at,
                     provider_ready_at, proof_auth_time, approved_by, completed_at)
                VALUES (gen_random_uuid(), 2, 1, gen_random_uuid(), 'ACTIVE', 1, 'PASSWORD_RESET', 'OWNER_REQUEST',
                        TIMESTAMPTZ '2026-11-18 00:00:00+00', gen_random_uuid(), TIMESTAMPTZ '2026-11-18 00:01:00+00',
                        TIMESTAMPTZ '2026-11-18 00:01:00+00', TIMESTAMPTZ '2026-11-18 00:01:01+00',
                        TIMESTAMPTZ '2026-11-18 00:01:02+00', 1, TIMESTAMPTZ '2026-11-18 00:01:03+00'),
                    (gen_random_uuid(), 3, 1, gen_random_uuid(), 'ACTIVE', 1, 'TOTP_RESET', 'OWNER_REQUEST',
                        TIMESTAMPTZ '2026-11-18 00:00:00+00', NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                """);
        String legacyAudit = """
                SELECT id, target_user_id, administrator_user_id, previous_status, session_version,
                       kind, proof_auth_time, approved_by, completed_at
                FROM helpdesk_account_recoveries ORDER BY target_user_id
                """;
        var before = jdbc.queryForList(legacyAudit);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForList(legacyAudit)).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM helpdesk_account_recoveries
                WHERE aws_requested_by IS NULL AND aws_approved_by IS NULL
                """, Long.class)).isEqualTo(2L);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO helpdesk_account_recoveries
                    (id, target_user_id, aws_requested_by, client_request_id, previous_status, session_version,
                     kind, reason, requested_at, provider_attempt_id, provider_started_at, provider_mutation_at,
                     provider_ready_at, proof_auth_time, aws_approved_by, completed_at)
                VALUES (gen_random_uuid(), 1, 'arn:aws:sts::123456789012:assumed-role/helpdesk-recovery/synthetic',
                        gen_random_uuid(), 'ACTIVE', 1, 'TOTP_RESET', 'SECURITY_REVIEW',
                        TIMESTAMPTZ '2026-11-18 00:00:00+00', gen_random_uuid(), TIMESTAMPTZ '2026-11-18 00:01:00+00',
                        TIMESTAMPTZ '2026-11-18 00:01:00+00', TIMESTAMPTZ '2026-11-18 00:01:01+00',
                        TIMESTAMPTZ '2026-11-18 00:01:02+00', ' ', TIMESTAMPTZ '2026-11-18 00:01:03+00')
                """)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("recovery_completion_requires_approval");
    }
}
