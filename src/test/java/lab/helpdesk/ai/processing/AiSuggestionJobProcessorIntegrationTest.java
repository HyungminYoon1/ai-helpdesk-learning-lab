package lab.helpdesk.ai.processing;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.JdbcAiSuggestionInputRepository;
import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.job.AiJobRequestKind;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import lab.helpdesk.ai.provider.AiProviderFailureException;
import lab.helpdesk.ai.provider.AiProviderFailureException.Kind;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ticket.TicketMessage;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import lab.helpdesk.ticket.repository.JdbcTicketMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Controlled Provider responses + real PostgreSQL; not a live AI quality evaluation. */
@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers
class AiSuggestionJobProcessorIntegrationTest {

    @Container
    @ServiceConnection
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.6-alpine");

    private static final String CONTACT_MARKER = "SYNTHETIC_EMAIL_VALUE";
    private static final String ORIGINAL_BODY = "새 링크로 로그인에 성공했습니다. 이유 문의. " + CONTACT_MARKER;
    private static final String VALID = """
            {"decision":"SUGGEST","summary":"로그인 복구 뒤 링크 만료 이유를 문의함",
             "categories":["ACCOUNT"],"priority":"NORMAL"}
            """;
    private static final String MISSING = """
            {"decision":"SUGGEST","summary":"로그인 문의","categories":["ACCOUNT"]}
            """;

    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private AiSuggestionJobClaimService claims;
    @Autowired private AiSuggestionResultService results;
    @Autowired private JdbcAiSuggestionInputRepository inputs;
    @Autowired private JdbcTicketMessageRepository messages;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clear_only_the_verified_testcontainer_database() {
        Boolean isolated = jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated)) {
            throw new IllegalStateException("processor test requires an isolated PostgreSQL database");
        }
        jdbc.execute("DROP TRIGGER IF EXISTS processor_result_fail ON ticket_suggestion_categories");
        jdbc.execute("DROP FUNCTION IF EXISTS processor_fail_category()");
        jdbc.update("DELETE FROM ticket_suggestion_categories");
        jdbc.update("DELETE FROM ticket_suggestions");
        jdbc.update("DELETE FROM ai_suggestion_attempts");
        jdbc.update("DELETE FROM ai_suggestion_jobs");
        jdbc.update("DELETE FROM ticket_messages");
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    void a_missing_job_or_an_old_ticket_without_message_never_calls_the_provider() {
        jdbc.update("INSERT INTO tickets (title, status) VALUES ('이전 문의', 'OPEN')");
        AtomicInteger calls = new AtomicInteger();

        assertThat(processor((input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        }).processNextPending().outcome()).isEqualTo(Outcome.NO_JOB);

        assertThat(calls).hasValue(0);
        assertThat(count("ai_suggestion_attempts")).isZero();
        assertThat(count("ticket_messages")).isZero();
        assertThat(count("ticket_suggestions")).isZero();
    }

    @Test
    void provider_runs_after_reservation_commit_without_a_transaction_or_job_row_lock() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionProcessingResult processed = processor((input, timeout, kind) -> {
            calls.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(status(receipt)).isEqualTo("RUNNING");
            assertThat(count("ai_suggestion_attempts")).isOne();
            assertThat(timeout).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(60));
            assertThat(kind).isEqualTo(AiJobRequestKind.INITIAL);
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    assertThat(jdbc.queryForList("""
                            SELECT id FROM ai_suggestion_jobs WHERE id = ? FOR UPDATE NOWAIT
                            """, Long.class, receipt.jobId())).containsExactly(receipt.jobId()));
            return VALID;
        }).processNextPending();

        assertThat(processed.outcome()).isEqualTo(Outcome.STORED);
        assertThat(calls).hasValue(1);
        assertThat(status(receipt)).isEqualTo("SUCCEEDED");
        assertThat(count("ticket_suggestions")).isOne();
        assertReceiptPreserved(receipt);
    }

    @Test
    void provider_gets_a_redacted_copy_of_the_fixed_input_message_not_the_latest_message() {
        TicketReceiptResult receipt = receive();
        messages.save(receipt.ticket().id(), new TicketMessage("나중에 추가한 본문", "later-author"));

        assertThat(processor((input, timeout, kind) -> {
            assertThat(input.title()).isEqualTo("로그인 문의");
            assertThat(input.body()).isEqualTo("새 링크로 로그인에 성공했습니다. 이유 문의. [EMAIL_REDACTED]");
            assertThat(input.toString()).doesNotContain(CONTACT_MARKER, "로그인", "EMAIL_REDACTED");
            return VALID;
        }).processNextPending().outcome()).isEqualTo(Outcome.STORED);

        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ?",
                String.class, receipt.messageId())).isEqualTo(ORIGINAL_BODY);
        assertThat(count("ticket_messages")).isEqualTo(2);
    }

    @Test
    void refusal_is_failed_not_abstained_and_does_not_erase_the_original_receipt() {
        TicketReceiptResult receipt = receive();

        assertThat(processor((input, timeout, kind) -> {
            throw new AiProviderFailureException(Kind.REFUSED);
        }).processNextPending().outcome()).isEqualTo(Outcome.FAILED);

        assertThat(status(receipt)).isEqualTo("FAILED");
        assertThat(failure(receipt)).isEqualTo("PROVIDER_REFUSED");
        assertThat(count("ticket_suggestions")).isZero();
        assertReceiptPreserved(receipt);
    }

    @Test
    void an_unconfirmed_provider_outcome_is_not_immediately_replayed_or_changed_to_abstain() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobProcessor processor = processor((input, timeout, kind) -> {
            calls.incrementAndGet();
            throw new AiProviderFailureException(Kind.OUTCOME_UNKNOWN);
        });

        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.PROVIDER_OUTCOME_UNKNOWN);
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.NO_JOB);

        assertThat(calls).hasValue(1);
        assertThat(status(receipt)).isEqualTo("RUNNING");
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertThat(count("ticket_suggestions")).isZero();
        assertReceiptPreserved(receipt);
    }

    @Test
    void a_confirmed_configuration_failure_is_terminal_without_automatic_replay() {
        TicketReceiptResult receipt = receive();
        AiSuggestionJobProcessor processor = processor((input, timeout, kind) -> {
            throw new AiProviderFailureException(Kind.CONFIGURATION);
        });

        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(failure(receipt)).isEqualTo("ADAPTER_CONFIGURATION_ERROR");
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertReceiptPreserved(receipt);
    }

    @Test
    void incomplete_provider_response_is_failed_not_repaired_or_stored() {
        TicketReceiptResult receipt = receive();
        assertThat(processor((input, timeout, kind) -> {
            throw new AiProviderFailureException(Kind.INVALID_RESPONSE);
        }).processNextPending().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(failure(receipt)).isEqualTo("OUTPUT_INVALID");
        assertThat(count("ticket_suggestions")).isZero();
        assertReceiptPreserved(receipt);
    }

    @Test
    void temporary_rejection_preserves_the_wait_hint_and_does_not_schedule_an_unchecked_retry() {
        TicketReceiptResult receipt = receive();
        AiSuggestionJobProcessor processor = processor((input, timeout, kind) -> {
            throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION,
                    AiProviderFailureException.Reason.RATE_LIMIT, Duration.ofSeconds(10));
        });
        AiProviderFailureException rejection = org.assertj.core.api.Assertions.catchThrowableOfType(
                AiProviderFailureException.class, processor::processNextPending);
        assertThat(rejection.retryAfter()).contains(Duration.ofSeconds(10));
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(status(receipt)).isEqualTo("RUNNING");
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertThat(count("ticket_suggestions")).isZero();
        assertReceiptPreserved(receipt);
    }

    @Test
    void invalid_json_is_rejected_without_repair_or_suggestion_storage() {
        TicketReceiptResult receipt = receive();

        assertThat(processor((input, timeout, kind) -> "{broken").processNextPending().outcome())
                .isEqualTo(Outcome.FAILED);

        assertThat(failure(receipt)).isEqualTo("OUTPUT_INVALID");
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertThat(count("ticket_suggestions")).isZero();
        assertReceiptPreserved(receipt);
    }

    @Test
    void extra_fields_are_not_treated_as_repairable_missing_fields() {
        TicketReceiptResult receipt = receive();

        assertThat(processor((input, timeout, kind) -> """
                {"decision":"SUGGEST","summary":"문의","categories":["ACCOUNT"],"ticketId":999}
                """).processNextPending().outcome()).isEqualTo(Outcome.FAILED);

        assertThat(failure(receipt)).isEqualTo("OUTPUT_INVALID");
        assertThat(count("ticket_suggestions")).isZero();
        assertReceiptPreserved(receipt);
    }

    @Test
    void a_missing_field_is_repaired_only_after_backoff_with_a_second_committed_reservation() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobProcessor processor = processor((input, timeout, kind) -> {
            int call = calls.incrementAndGet();
            if (call == 1) return MISSING;
            assertThat(kind).isEqualTo(AiJobRequestKind.OUTPUT_REPAIR);
            assertThat(count("ai_suggestion_attempts")).isEqualTo(2);
            return VALID;
        });

        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.OUTPUT_REPAIR_SCHEDULED);
        assertThat(status(receipt)).isEqualTo("PENDING");
        assertThat(count("ticket_suggestions")).isZero();
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(1);
        makeRepairDue(receipt);
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.STORED);
        assertThat(calls).hasValue(2);
        assertThat(jdbc.queryForObject("""
                SELECT reserved_output_repair_count FROM ai_suggestion_jobs WHERE id = ?
                """, Integer.class, receipt.jobId())).isOne();
        assertReceiptPreserved(receipt);
    }

    @Test
    void repeated_missing_fields_stop_at_the_shared_repair_limit() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobProcessor processor = processor((input, timeout, kind) -> {
            calls.incrementAndGet();
            return MISSING;
        });

        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.OUTPUT_REPAIR_SCHEDULED);
        makeRepairDue(receipt);
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(2);
        assertThat(failure(receipt)).isEqualTo("OUTPUT_REPAIR_LIMIT_EXHAUSTED");
        assertThat(count("ticket_suggestions")).isZero();
        assertReceiptPreserved(receipt);
    }

    @Test
    void the_last_already_reserved_generation_is_still_allowed_to_send_and_store() {
        TicketReceiptResult receipt = receive();
        jdbc.update("UPDATE ai_suggestion_jobs SET max_generation_attempts = 1 WHERE id = ?", receipt.jobId());

        assertThat(processor((input, timeout, kind) -> VALID).processNextPending().outcome())
                .isEqualTo(Outcome.STORED);

        assertThat(count("ai_suggestion_attempts")).isOne();
        assertThat(count("ticket_suggestions")).isOne();
    }

    @Test
    void a_valid_abstain_has_no_suggestion_but_a_committed_abstained_job() {
        TicketReceiptResult receipt = receive();

        assertThat(processor((input, timeout, kind) -> """
                {"decision":"ABSTAIN","summary":null,"categories":null,"priority":null}
                """).processNextPending().outcome()).isEqualTo(Outcome.ABSTAINED);

        assertThat(status(receipt)).isEqualTo("ABSTAINED");
        assertThat(count("ticket_suggestions")).isZero();
        assertReceiptPreserved(receipt);
    }

    @Test
    void the_source_reader_rejects_a_replaced_attempt_and_a_mismatching_input_message() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThat(inputs.findCurrentInput(claim).orElseThrow().messageId()).isEqualTo(receipt.messageId());
        assertThat(inputs.canSendReservedRequest(claim)).isTrue();
        long otherMessage = messages.save(receipt.ticket().id(), new TicketMessage("새 메시지", "later-author"));
        AiJobClaim mismatching = new AiJobClaim(claim.jobId(), otherMessage, claim.attemptNumber(),
                claim.reservedGenerationCount(), claim.reservedOutputRepairCount(), claim.requestKind(),
                claim.policy(), claim.firstStartedAt(), claim.processingDeadlineAt(), claim.leaseExpiresAt());
        assertThat(inputs.findCurrentInput(mismatching)).isEmpty();
        assertThat(inputs.canSendReservedRequest(mismatching)).isFalse();
        jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE id = ?",
                receipt.jobId());
        assertThat(inputs.canSendReservedRequest(claim)).isFalse();
        assertThat(claims.claimRecoveryAfterResultCheck(claim.jobId(), 1)).isPresent();
        assertThat(inputs.findCurrentInput(claim)).isEmpty();
        assertThat(inputs.canSendReservedRequest(claim)).isFalse();
    }

    @Test
    void a_result_rollback_retains_the_validated_object_and_retrying_storage_does_not_call_ai() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        jdbc.execute("""
                CREATE FUNCTION processor_fail_category() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'CONTROLLED_RESULT_STORAGE_FAILURE'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER processor_result_fail BEFORE INSERT ON ticket_suggestion_categories
                FOR EACH ROW EXECUTE FUNCTION processor_fail_category()
                """);
        AiSuggestionJobProcessor processor = processor((input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        });

        AiSuggestionProcessingResult failed = processor.processNextPending();

        assertThat(failed.outcome()).isEqualTo(Outcome.STORAGE_PENDING);
        assertThat(failed.pendingStorageOutput()).isNotNull();
        assertThat(failed.toString()).doesNotContain("로그인", CONTACT_MARKER);
        assertThat(status(receipt)).isEqualTo("RUNNING");
        assertThat(count("ticket_suggestions")).isZero();
        assertReceiptPreserved(receipt);
        jdbc.execute("DROP TRIGGER processor_result_fail ON ticket_suggestion_categories");
        assertThat(processor.storeValidatedResult(failed.claim(), failed.pendingStorageOutput()).outcome())
                .isEqualTo(Outcome.STORED);
        assertThat(calls).hasValue(1);
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertThat(count("ticket_suggestions")).isOne();
    }

    @Test
    void invalid_stored_input_is_rejected_before_a_provider_request() {
        TicketReceiptResult receipt = receive();
        jdbc.update("UPDATE ticket_messages SET body = ? WHERE id = ?", "가".repeat(2001), receipt.messageId());
        AtomicInteger calls = new AtomicInteger();

        assertThat(processor((input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        }).processNextPending().outcome()).isEqualTo(Outcome.FAILED);

        assertThat(calls).hasValue(0);
        assertThat(count("ticket_suggestions")).isZero();
        assertThat(status(receipt)).isEqualTo("FAILED");
    }

    @Test
    void an_outer_transaction_is_rejected_before_claiming_or_sending() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobProcessor processor = processor((input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        });

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx -> processor.processNextPending()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_PROCESSOR_REQUIRES_NO_OUTER_TRANSACTION");

        assertThat(status(receipt)).isEqualTo("PENDING");
        assertThat(calls).hasValue(0);
        assertThat(count("ai_suggestion_attempts")).isZero();
    }

    @Test
    void unexpected_adapter_errors_do_not_expose_input_or_trigger_another_request() {
        TicketReceiptResult receipt = receive();
        AiSuggestionJobProcessor processor = processor((input, timeout, kind) -> {
            throw new IllegalStateException(CONTACT_MARKER);
        });

        assertThatThrownBy(processor::processNextPending).isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_PROVIDER_ADAPTER_FAILED").hasNoCause();

        assertThat(status(receipt)).isEqualTo("RUNNING");
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertThat(count("ticket_suggestions")).isZero();
    }

    private AiSuggestionJobProcessor processor(AiSuggestionProvider provider) {
        return new AiSuggestionJobProcessor(claims, inputs,
                new AiInputPrivacyGuard(List.of(new SensitiveFragment(CONTACT_MARKER, SensitiveType.EMAIL))),
                provider, new AiSuggestionOutputValidator(200), results, Clock.systemUTC());
    }

    private TicketReceiptResult receive() {
        return receipts.receive("로그인 문의", ORIGINAL_BODY, "synthetic-author");
    }

    private void makeRepairDue(TicketReceiptResult receipt) {
        jdbc.update("UPDATE ai_suggestion_jobs SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                receipt.jobId());
    }

    private String status(TicketReceiptResult receipt) {
        return jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId());
    }

    private String failure(TicketReceiptResult receipt) {
        return jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId());
    }

    private long count(String table) {
        // Only literal Table names above, never HTTP or model input.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertReceiptPreserved(TicketReceiptResult receipt) {
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ?",
                String.class, receipt.messageId())).isEqualTo(ORIGINAL_BODY);
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?",
                String.class, receipt.ticket().id())).isEqualTo("OPEN");
    }
}
