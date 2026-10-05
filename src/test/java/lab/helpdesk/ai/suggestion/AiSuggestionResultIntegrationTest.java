package lab.helpdesk.ai.suggestion;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.job.AiJobStatus;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.job.JdbcAiSuggestionJobResultRepository;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Category;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Priority;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers
class AiSuggestionResultIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    // 200은 이 Test의 검증기 입력이다. Runtime의 요약 상한을 새로 확정하지 않는다.
    private final AiSuggestionOutputValidator validator = new AiSuggestionOutputValidator(200);

    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private AiSuggestionJobClaimService claims;
    @Autowired private AiSuggestionResultService results;
    @Autowired private JdbcTicketSuggestionRepository suggestions;
    @Autowired private JdbcAiSuggestionJobResultRepository jobs;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clear_only_the_verified_isolated_test_database() {
        Boolean isolated = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated)) {
            throw new IllegalStateException("result test cleanup requires the isolated test database");
        }
        jdbcTemplate.update("DELETE FROM ticket_suggestion_categories");
        jdbcTemplate.update("DELETE FROM ticket_suggestions");
        jdbcTemplate.update("DELETE FROM ai_suggestion_attempts");
        jdbcTemplate.update("DELETE FROM ai_suggestion_jobs");
        jdbcTemplate.update("DELETE FROM ticket_messages");
        jdbcTemplate.update("DELETE FROM tickets");
    }

    @Test
    void result_service_commits_the_suggestion_categories_and_job_completion_together() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();

        assertThat(results.complete(claim, multipleCategories())).isEqualTo(AiSuggestionCompletion.STORED);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        AiSuggestionStoredResult result = results.findStoredResult(receipt.jobId()).orElseThrow();
        TicketSuggestion suggestion = result.suggestion().orElseThrow();
        assertThat(result.job().status()).isEqualTo(AiJobStatus.SUCCEEDED);
        assertThat(result.job().currentAttempt()).isOne();
        assertThat(suggestion.jobId()).isEqualTo(receipt.jobId());
        assertThat(suggestion.summary()).isEqualTo("로그인과 이중 결제에 대한 문의");
        assertThat(suggestion.categories()).containsExactly(Category.ACCOUNT, Category.BILLING);
        assertThat(suggestion.priority()).isEqualTo(Priority.HIGH);
        assertThat(suggestion.reviewStatus()).isEqualTo(TicketSuggestion.ReviewStatus.PENDING_REVIEW);
        assertThat(suggestion.createdAt()).isNotNull();
        assertResultRows(1, 2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT finished_at IS NOT NULL AND lease_expires_at IS NULL
                    AND next_attempt_at IS NULL AND last_failure_code IS NULL
                FROM ai_suggestion_jobs WHERE id = ?
                """, Boolean.class, receipt.jobId())).isTrue();
        assertReceiptPreserved(receipt);
    }

    @Test
    void undetermined_values_and_html_like_summary_are_preserved_without_becoming_facts_or_markup() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        ContractValidatedOutput output = validator.validate("""
                {"decision":"SUGGEST","summary":"  <strong>확인 요청</strong>  ",
                 "categories":["UNDETERMINED"],"priority":"UNDETERMINED"}
                """);

        assertThat(results.complete(claim, output)).isEqualTo(AiSuggestionCompletion.STORED);

        TicketSuggestion stored = results.findStoredResult(claim.jobId()).orElseThrow().suggestion().orElseThrow();
        assertThat(stored.summary()).isEqualTo("  <strong>확인 요청</strong>  ");
        assertThat(stored.categories()).containsExactly(Category.UNDETERMINED);
        assertThat(stored.priority()).isEqualTo(Priority.UNDETERMINED);
        assertThat(stored.reviewStatus()).isEqualTo(TicketSuggestion.ReviewStatus.PENDING_REVIEW);
    }

    @Test
    void abstain_commits_its_distinct_job_state_without_a_suggestion() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();

        assertThat(results.complete(claim, abstain())).isEqualTo(AiSuggestionCompletion.ABSTAINED);

        AiSuggestionStoredResult stored = results.findStoredResult(receipt.jobId()).orElseThrow();
        assertThat(stored.job().status()).isEqualTo(AiJobStatus.ABSTAINED);
        assertThat(stored.suggestion()).isEmpty();
        assertResultRows(0, 0);
        assertReceiptPreserved(receipt);
    }

    @Test
    void invalid_output_is_rejected_before_the_result_transaction_and_leaves_original_rows_intact() {
        TicketReceiptResult receipt = receive();
        claims.claimNextPending().orElseThrow();

        assertThatThrownBy(() -> validator.validate("""
                {"decision":"SUGGEST","summary":"로그인 문의","categories":["ACCOUNT"]}
                """)).isInstanceOf(AiSuggestionOutputValidator.InvalidOutputException.class);

        assertThat(status(receipt.jobId())).isEqualTo("RUNNING");
        assertResultRows(0, 0);
        assertReceiptPreserved(receipt);
    }

    @Test
    void a_late_replaced_attempt_cannot_store_a_suggestion_or_abstain() {
        receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        expireLease(first.jobId());
        AiJobClaim second = claims.claimRecoveryAfterResultCheck(first.jobId(), first.attemptNumber()).orElseThrow();

        assertThat(results.complete(first, multipleCategories())).isEqualTo(AiSuggestionCompletion.NOT_CURRENT);
        assertThat(results.complete(first, abstain())).isEqualTo(AiSuggestionCompletion.NOT_CURRENT);
        assertThat(status(first.jobId())).isEqualTo("RUNNING");
        assertResultRows(0, 0);
        assertThat(results.complete(second, multipleCategories())).isEqualTo(AiSuggestionCompletion.STORED);
        assertThat(results.findStoredResult(first.jobId()).orElseThrow().job().currentAttempt()).isEqualTo(2);
    }

    @Test
    void pending_repair_does_not_accept_the_previous_response_as_a_current_running_attempt() {
        receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        assertThat(claims.scheduleOutputRepair(first)).isTrue();

        assertThat(results.complete(first, multipleCategories())).isEqualTo(AiSuggestionCompletion.NOT_CURRENT);

        assertThat(status(first.jobId())).isEqualTo("PENDING");
        assertResultRows(0, 0);
    }

    @Test
    void spending_the_generation_cap_does_not_forbid_the_current_valid_response() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        for (int attempt = 2; attempt <= 3; attempt++) {
            expireLease(claim.jobId());
            claim = claims.claimRecoveryAfterResultCheck(claim.jobId(), claim.attemptNumber()).orElseThrow();
        }
        assertThat(claim.reservedGenerationCount()).isEqualTo(claim.policy().maxGenerationAttempts());
        assertThat(results.complete(claim, multipleCategories())).isEqualTo(AiSuggestionCompletion.STORED);
        assertThat(reservationCount()).isEqualTo(3);
        assertResultRows(1, 2);
    }

    @Test
    void an_expired_lease_alone_does_not_replace_the_current_attempt() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        expireLease(claim.jobId());

        assertThat(results.complete(claim, multipleCategories())).isEqualTo(AiSuggestionCompletion.STORED);
        assertThat(reservationCount()).isOne();
    }

    @Test
    void the_processing_deadline_blocks_a_late_result_without_creating_a_new_attempt() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        jdbcTemplate.update("""
                UPDATE ai_suggestion_jobs
                SET first_started_at = clock_timestamp() - INTERVAL '10 minutes',
                    processing_deadline_at = clock_timestamp() - INTERVAL '1 minute',
                    lease_expires_at = clock_timestamp() - INTERVAL '2 minutes'
                WHERE id = ?
                """, claim.jobId());

        assertThat(results.complete(claim, multipleCategories())).isEqualTo(AiSuggestionCompletion.NOT_CURRENT);
        assertResultRows(0, 0);
        assertThat(reservationCount()).isOne();
    }

    @Test
    void repeating_completion_neither_overwrites_the_suggestion_nor_inserts_another_one() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThat(results.complete(claim, multipleCategories())).isEqualTo(AiSuggestionCompletion.STORED);
        ContractValidatedOutput differentOutput = validator.validate("""
                {"decision":"SUGGEST","summary":"뒤늦게 제출한 다른 요약",
                 "categories":["OTHER"],"priority":"NORMAL"}
                """);

        assertThat(results.complete(claim, differentOutput)).isEqualTo(AiSuggestionCompletion.NOT_CURRENT);
        assertThat(results.complete(claim, abstain())).isEqualTo(AiSuggestionCompletion.NOT_CURRENT);

        assertThat(results.findStoredResult(claim.jobId()).orElseThrow().suggestion().orElseThrow().summary())
                .isEqualTo("로그인과 이중 결제에 대한 문의");
        assertResultRows(1, 2);
        assertThat(reservationCount()).isOne();
    }

    @Test
    void concurrent_completion_of_the_same_attempt_commits_only_one_result() throws Exception {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        ContractValidatedOutput output = multipleCategories();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return results.complete(claim, output); });
            var second = executor.submit(() -> { await(start); return results.complete(claim, output); });
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(AiSuggestionCompletion.STORED, AiSuggestionCompletion.NOT_CURRENT);
        }
        assertResultRows(1, 2);
    }

    @Test
    void category_storage_failure_rolls_back_the_parent_suggestion_and_preserves_the_receipt() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        jdbcTemplate.execute("""
                ALTER TABLE ticket_suggestion_categories
                ADD CONSTRAINT test_reject_billing CHECK (category <> 'BILLING') NOT VALID
                """);
        try {
            assertSafeStorageFailure(() -> results.complete(claim, multipleCategories()));
            assertResultRows(0, 0);
            assertThat(status(receipt.jobId())).isEqualTo("RUNNING");
            assertReceiptPreserved(receipt);
        } finally {
            jdbcTemplate.execute("ALTER TABLE ticket_suggestion_categories DROP CONSTRAINT test_reject_billing");
        }
    }

    @Test
    void job_completion_failure_rolls_back_all_result_rows_and_db_save_can_be_retried_without_a_new_reservation() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        ContractValidatedOutput validatedOutput = multipleCategories();
        jdbcTemplate.execute("""
                ALTER TABLE ai_suggestion_jobs
                ADD CONSTRAINT test_reject_success CHECK (status <> 'SUCCEEDED') NOT VALID
                """);
        try {
            assertSafeStorageFailure(() -> results.complete(claim, validatedOutput));
            assertResultRows(0, 0);
            assertThat(status(receipt.jobId())).isEqualTo("RUNNING");
            assertReceiptPreserved(receipt);
        } finally {
            jdbcTemplate.execute("ALTER TABLE ai_suggestion_jobs DROP CONSTRAINT test_reject_success");
        }

        assertThat(results.complete(claim, validatedOutput)).isEqualTo(AiSuggestionCompletion.STORED);
        assertThat(reservationCount()).isOne();
        assertResultRows(1, 2);
    }

    @Test
    void rollback_after_successful_sql_restores_running_instead_of_automatically_marking_failed() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            assertThat(jobs.lockCurrentExecution(claim)).isTrue();
            assertThat(suggestions.save(claim.jobId(), multipleCategories())).isPositive();
            assertResultRows(1, 2); // INSERT 성공과 Commit 성공을 구분한다.
            assertThat(jobs.finishIfCurrent(claim, AiJobStatus.SUCCEEDED)).isTrue();
            assertThat(status(claim.jobId())).isEqualTo("SUCCEEDED");
            throw new IllegalStateException("test_failure_after_result_sql");
        })).isInstanceOf(IllegalStateException.class).hasMessage("test_failure_after_result_sql");

        assertResultRows(0, 0);
        assertThat(status(claim.jobId())).isEqualTo("RUNNING");
        assertReceiptPreserved(receipt);
    }

    @Test
    void abstain_status_storage_failure_is_not_reported_as_a_committed_abstention() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        jdbcTemplate.execute("""
                ALTER TABLE ai_suggestion_jobs
                ADD CONSTRAINT test_reject_abstain CHECK (status <> 'ABSTAINED') NOT VALID
                """);
        try {
            assertSafeStorageFailure(() -> results.complete(claim, abstain()));
            assertThat(status(claim.jobId())).isEqualTo("RUNNING");
            assertResultRows(0, 0);
            assertReceiptPreserved(receipt);
        } finally {
            jdbcTemplate.execute("ALTER TABLE ai_suggestion_jobs DROP CONSTRAINT test_reject_abstain");
        }
    }

    @Test
    void database_constraints_reject_duplicate_jobs_categories_unknown_values_and_blank_summaries() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        results.complete(claim, multipleCategories());
        long suggestionId = results.findStoredResult(claim.jobId()).orElseThrow().suggestion().orElseThrow().id();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ticket_suggestions (job_id, summary, priority) VALUES (?, ?, 'NORMAL')
                """, claim.jobId(), "중복 제안")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ticket_suggestion_categories (suggestion_id, category) VALUES (?, 'ACCOUNT')
                """, suggestionId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ticket_suggestion_categories (suggestion_id, category) VALUES (?, 'INVALID')
                """, suggestionId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ticket_suggestion_categories (suggestion_id, category) VALUES (?, 'OTHER')
                """, Long.MAX_VALUE)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE ticket_suggestions SET priority = 'URGENT' WHERE id = ?
                """, suggestionId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE ticket_suggestions SET summary = ? WHERE id = ?
                """, "\t\n\u00a0\u3000", suggestionId)).isInstanceOf(DataIntegrityViolationException.class);
        assertResultRows(1, 2);
    }

    @Test
    void result_lookup_distinguishes_absent_jobs_pending_jobs_and_committed_results() {
        assertThat(results.findStoredResult(Long.MAX_VALUE)).isEmpty();
        TicketReceiptResult receipt = receive();
        AiSuggestionStoredResult pending = results.findStoredResult(receipt.jobId()).orElseThrow();
        assertThat(pending.job().status()).isEqualTo(AiJobStatus.PENDING);
        assertThat(pending.suggestion()).isEmpty();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        results.complete(claim, multipleCategories());

        AiSuggestionStoredResult completed = results.findStoredResult(receipt.jobId()).orElseThrow();
        assertThat(completed.job().status()).isEqualTo(AiJobStatus.SUCCEEDED);
        assertThat(completed.suggestion()).isPresent();
        assertThat(reservationCount()).isOne();
        // Commit 응답 유실을 실제로 주입한 Test는 아니다. 재조회 기능과 저장값을 확인한다.
    }

    @Test
    void result_writes_require_a_transaction_and_reject_abstain_as_a_suggestion_row() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThatThrownBy(() -> jobs.lockCurrentExecution(claim)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> suggestions.save(claim.jobId(), multipleCategories()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
                suggestions.save(claim.jobId(), abstain()))).isInstanceOf(IllegalArgumentException.class);
        assertResultRows(0, 0);
    }

    private TicketReceiptResult receive() {
        return receipts.receive("원래 문의 제목", "  고객이 작성한 원문\n그대로 보존합니다.  ", "synthetic-author");
    }

    private ContractValidatedOutput multipleCategories() {
        return validator.validate("""
                {"decision":"SUGGEST","summary":"로그인과 이중 결제에 대한 문의",
                 "categories":["BILLING","ACCOUNT"],"priority":"HIGH"}
                """);
    }

    private ContractValidatedOutput abstain() {
        return validator.validate("""
                {"decision":"ABSTAIN","summary":null,"categories":null,"priority":null}
                """);
    }

    private void expireLease(long jobId) {
        jdbcTemplate.update("""
                UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 minute'
                WHERE id = ?
                """, jobId);
    }

    private String status(long jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, jobId);
    }

    private long reservationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts", Long.class);
    }

    private void assertResultRows(long suggestionsExpected, long categoriesExpected) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_suggestions", Long.class))
                .isEqualTo(suggestionsExpected);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_suggestion_categories", Long.class))
                .isEqualTo(categoriesExpected);
    }

    private void assertReceiptPreserved(TicketReceiptResult receipt) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tickets", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_messages", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM tickets WHERE id = ?", String.class,
                receipt.ticket().id())).isEqualTo("원래 문의 제목");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class,
                receipt.ticket().id())).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject("SELECT body FROM ticket_messages WHERE id = ?", String.class,
                receipt.messageId())).isEqualTo("  고객이 작성한 원문\n그대로 보존합니다.  ");
        assertThat(jdbcTemplate.queryForObject("SELECT author_username FROM ticket_messages WHERE id = ?", String.class,
                receipt.messageId())).isEqualTo("synthetic-author");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_suggestion_jobs", Long.class)).isOne();
    }

    private static void assertSafeStorageFailure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(AiSuggestionStorageException.class)
                .hasMessage("AI_RESULT_STORAGE_FAILED").hasNoCause();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("result test synchronization timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("result test synchronization interrupted");
        }
    }
}
