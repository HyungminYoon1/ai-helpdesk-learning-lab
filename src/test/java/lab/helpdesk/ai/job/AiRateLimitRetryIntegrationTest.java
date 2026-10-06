package lab.helpdesk.ai.job;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.Executors;

import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL retry control; no AI Provider is constructed or called. */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.main.banner-mode=off"})
@ActiveProfiles("postgres")
@Testcontainers
class AiRateLimitRetryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private AiSuggestionJobClaimService claims;
    @Autowired private JdbcAiSuggestionJobExecutionRepository execution;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clear_only_the_verified_testcontainer_database() {
        Boolean isolated = jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated)) {
            throw new IllegalStateException("AI_RETRY_TEST_DATABASE_REQUIRED");
        }
        jdbc.update("DELETE FROM ticket_suggestion_categories");
        jdbc.update("DELETE FROM ticket_suggestions");
        jdbc.update("DELETE FROM ai_suggestion_attempts");
        jdbc.update("DELETE FROM ai_suggestion_jobs");
        jdbc.update("DELETE FROM ticket_messages");
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    void the_longer_provider_wait_is_persisted_without_an_early_reservation() {
        TicketReceiptResult receipt = receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        OffsetDateTime before = databaseNow();

        assertThat(claims.scheduleRateLimitRetry(first, Duration.ofSeconds(15)))
                .isEqualTo(AiJobRetryScheduleOutcome.SCHEDULED);

        OffsetDateTime after = databaseNow();
        OffsetDateTime next = nextAttempt(receipt.jobId());
        assertThat(next).isBetween(before.plusSeconds(15), after.plusSeconds(15));
        assertThat(status(receipt.jobId())).isEqualTo("PENDING");
        assertThat(failure(receipt.jobId())).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(claims.claimNextPending()).isEmpty();
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertReceiptPreserved(receipt);
    }

    @Test
    void a_shorter_provider_wait_does_not_reduce_the_job_backoff() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        OffsetDateTime before = databaseNow();

        assertThat(claims.scheduleRateLimitRetry(claim, Duration.ofSeconds(2)))
                .isEqualTo(AiJobRetryScheduleOutcome.SCHEDULED);

        assertThat(nextAttempt(receipt.jobId()))
                .isBetween(before.plusSeconds(5), databaseNow().plusSeconds(5));
    }

    @Test
    void a_retry_rechecks_and_commits_a_new_reservation_without_spending_a_repair() {
        TicketReceiptResult receipt = receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        claims.scheduleRateLimitRetry(first, Duration.ofSeconds(15));
        makeDue(receipt.jobId());

        AiJobClaim second = claims.claimNextPending().orElseThrow();

        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(second.reservedGenerationCount()).isEqualTo(2);
        assertThat(second.reservedOutputRepairCount()).isZero();
        assertThat(second.requestKind()).isEqualTo(AiJobRequestKind.TEMPORARY_RETRY);
        assertThat(second.policy()).isEqualTo(first.policy());
        assertThat(second.firstStartedAt()).isEqualTo(first.firstStartedAt());
        assertThat(second.processingDeadlineAt()).isEqualTo(first.processingDeadlineAt());
        assertThat(count("ai_suggestion_attempts")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT request_kind FROM ai_suggestion_attempts ORDER BY attempt_number",
                String.class)).containsExactly("INITIAL", "TEMPORARY_RETRY");
    }

    @Test
    void a_wait_beyond_the_deadline_is_not_shortened_and_the_receipt_survives_expiry() {
        TicketReceiptResult receipt = receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        jdbc.update("UPDATE ai_suggestion_jobs SET processing_deadline_at = clock_timestamp() + INTERVAL '10 seconds', "
                + "lease_expires_at = clock_timestamp() + INTERVAL '5 seconds' WHERE id = ?", receipt.jobId());

        assertThat(claims.scheduleRateLimitRetry(first, Duration.ofSeconds(15)))
                .isEqualTo(AiJobRetryScheduleOutcome.SCHEDULED);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > processing_deadline_at "
                + "FROM ai_suggestion_jobs WHERE id = ?", Boolean.class, receipt.jobId())).isTrue();
        assertThat(claims.claimNextPending()).isEmpty();

        // Move only this isolated test Job's clock metadata to an expired boundary.
        jdbc.update("UPDATE ai_suggestion_jobs SET first_started_at = clock_timestamp() - INTERVAL '1 minute', "
                + "processing_deadline_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", receipt.jobId());
        assertThat(claims.claimNextPending()).isEmpty();
        assertThat(status(receipt.jobId())).isEqualTo("FAILED");
        assertThat(failure(receipt.jobId())).isEqualTo("JOB_PROCESSING_DEADLINE_EXCEEDED");
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertReceiptPreserved(receipt);
    }

    @Test
    void an_exhausted_generation_cap_finishes_the_job_without_resetting_the_count() {
        TicketReceiptResult receipt = receive();
        jdbc.update("UPDATE ai_suggestion_jobs SET max_generation_attempts = 1 WHERE id = ?", receipt.jobId());
        AiJobClaim first = claims.claimNextPending().orElseThrow();

        assertThat(claims.scheduleRateLimitRetry(first, Duration.ofSeconds(15)))
                .isEqualTo(AiJobRetryScheduleOutcome.FAILED);
        assertThat(status(receipt.jobId())).isEqualTo("FAILED");
        assertThat(failure(receipt.jobId())).isEqualTo("GENERATION_LIMIT_EXHAUSTED");
        assertThat(claims.claimNextPending()).isEmpty();
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertReceiptPreserved(receipt);
    }

    @Test
    void a_late_previous_attempt_cannot_reschedule_the_current_execution() {
        TicketReceiptResult receipt = receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        claims.scheduleRateLimitRetry(first, Duration.ofSeconds(15));
        makeDue(receipt.jobId());
        AiJobClaim second = claims.claimNextPending().orElseThrow();

        assertThat(claims.scheduleRateLimitRetry(first, Duration.ofSeconds(15)))
                .isEqualTo(AiJobRetryScheduleOutcome.NOT_CURRENT);
        assertThat(status(receipt.jobId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT current_attempt FROM ai_suggestion_jobs WHERE id = ?",
                Integer.class, receipt.jobId())).isEqualTo(second.attemptNumber());
        assertThat(count("ai_suggestion_attempts")).isEqualTo(2);
    }

    @Test
    void a_failed_job_is_not_reopened_by_scheduling_or_polling() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThat(claims.failIfCurrent(claim, AiJobFailureCode.ADAPTER_CONFIGURATION_ERROR)).isTrue();

        assertThat(claims.scheduleRateLimitRetry(claim, Duration.ofSeconds(15)))
                .isEqualTo(AiJobRetryScheduleOutcome.NOT_CURRENT);
        assertThat(claims.claimNextPending()).isEmpty();
        assertThat(status(receipt.jobId())).isEqualTo("FAILED");
        assertThat(count("ai_suggestion_attempts")).isOne();
    }

    @Test
    void concurrent_schedule_requests_do_not_create_reservations_or_replace_each_other() throws Exception {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> claims.scheduleRateLimitRetry(claim, Duration.ofSeconds(15)));
            var two = executor.submit(() -> claims.scheduleRateLimitRetry(claim, Duration.ofSeconds(15)));
            assertThat(java.util.List.of(one.get(), two.get())).containsExactlyInAnyOrder(
                    AiJobRetryScheduleOutcome.SCHEDULED, AiJobRetryScheduleOutcome.NOT_CURRENT);
        }
        assertThat(count("ai_suggestion_attempts")).isOne();
    }

    @Test
    void negative_or_overflowing_delays_are_rejected_without_a_new_reservation() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThatThrownBy(() -> claims.scheduleRateLimitRetry(claim, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_RETRY_DELAY_INVALID").hasNoCause();
        assertThatThrownBy(() -> claims.scheduleRateLimitRetry(claim, Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_RETRY_DELAY_INVALID").hasNoCause();
        assertThat(count("ai_suggestion_attempts")).isOne();
    }

    @Test
    void direct_retry_mutations_require_a_transaction() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThatThrownBy(() -> execution.scheduleRateLimitRetry(claim, 15000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.failForExhaustedGeneration(claim))
                .isInstanceOf(IllegalStateException.class);
    }

    private TicketReceiptResult receive() {
        return receipts.receive("Synthetic retry question", "Original message must remain.", "synthetic-author");
    }

    private OffsetDateTime databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    private OffsetDateTime nextAttempt(long jobId) {
        return jdbc.queryForObject("SELECT next_attempt_at FROM ai_suggestion_jobs WHERE id = ?",
                OffsetDateTime.class, jobId);
    }

    private void makeDue(long jobId) {
        jdbc.update("UPDATE ai_suggestion_jobs SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                jobId);
    }

    private String status(long jobId) {
        return jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, jobId);
    }

    private String failure(long jobId) {
        return jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?", String.class, jobId);
    }

    private long count(String table) {
        // Literal test-owned table names only, never browser or AI input.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertReceiptPreserved(TicketReceiptResult receipt) {
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ?",
                String.class, receipt.messageId())).isEqualTo("Original message must remain.");
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?",
                String.class, receipt.ticket().id())).isEqualTo("OPEN");
        assertThat(count("ticket_suggestions")).isZero();
    }
}
