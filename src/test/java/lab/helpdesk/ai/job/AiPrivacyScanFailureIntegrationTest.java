package lab.helpdesk.ai.job;

import static lab.helpdesk.ai.input.AiPrivacyScanFixtures.completedEmptyScan;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.JdbcAiSuggestionInputRepository;
import lab.helpdesk.ai.processing.AiSuggestionJobProcessor;
import lab.helpdesk.ai.processing.AiSuggestionJobWorker;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Failure persistence and read/poll behavior, not a detector or live AI experiment. */
@lab.helpdesk.identity.WithIdentityFixtures
@SpringBootTest(properties = {
        "logging.level.root=WARN", "spring.main.banner-mode=off",
        "helpdesk.ai.provider.enabled=false", "helpdesk.ai.worker.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("postgres")
@Testcontainers
@Import(AiPrivacyScanFailureIntegrationTest.TestUsers.class)
class AiPrivacyScanFailureIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    private static final String ORIGINAL = "Synthetic original message: login succeeded with a new link.";
    private static final List<String> TABLES = List.of("tickets", "ticket_messages", "ai_suggestion_jobs",
            "ai_suggestion_attempts", "ticket_suggestions", "ticket_suggestion_categories");

    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private AiSuggestionJobClaimService claims;
    @Autowired private AiSuggestionResultService results;
    @Autowired private JdbcAiSuggestionInputRepository inputs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    @BeforeEach
    void clear_only_the_verified_testcontainer_database() {
        Boolean isolated = jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated)) throw new IllegalStateException("AI_PRIVACY_TEST_DATABASE_REQUIRED");
        jdbc.update("DELETE FROM ticket_suggestion_categories");
        jdbc.update("DELETE FROM ticket_suggestions");
        jdbc.update("DELETE FROM ai_suggestion_attempts");
        jdbc.update("DELETE FROM ai_suggestion_jobs");
        jdbc.update("DELETE FROM ticket_messages");
        jdbc.update("DELETE FROM tickets");
    }

    @ParameterizedTest
    @EnumSource(value = AiJobFailureCode.class, names = {"PRIVACY_SCAN_TIMEOUT", "PRIVACY_SCAN_INVALID_RESULT"})
    void scan_failure_preserves_receipt_and_is_read_only_without_poll_retry(AiJobFailureCode code) throws Exception {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();

        assertThat(claims.failPrivacyScanIfCurrent(claim, code)).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?",
                String.class, receipt.jobId())).isEqualTo(code.name());
        assertThat(jdbc.queryForObject("SELECT result_code FROM ai_suggestion_attempts WHERE job_id = ?",
                String.class, receipt.jobId())).isEqualTo("AUTO_RETRY_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT reserved_generation_count FROM ai_suggestion_jobs WHERE id = ?",
                Integer.class, receipt.jobId())).isOne();
        assertThat(jdbc.queryForObject("SELECT body = ? AND author_username = ? FROM ticket_messages WHERE id = ?",
                Boolean.class, ORIGINAL, "synthetic-author", receipt.messageId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?",
                String.class, receipt.ticket().id())).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions", Long.class)).isZero();
        var before = snapshot();

        AtomicInteger providerCalls = new AtomicInteger();
        var processor = new AiSuggestionJobProcessor(claims, inputs,
                completedEmptyScan(new AiInputPrivacyGuard(List.of(
                        new SensitiveFragment("synthetic@example.invalid", SensitiveType.EMAIL)))),
                (input, timeout, kind) -> {
                    providerCalls.incrementAndGet();
                    throw new IllegalStateException("PROVIDER_MUST_NOT_RUN");
                }, new AiSuggestionOutputValidator(200), results, Clock.systemUTC());
        var worker = new AiSuggestionJobWorker(processor, claims);
        for (int i = 0; i < 3; i++) {
            assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
            mvc.perform(get("/api/tickets/{id}/ai-suggestion", receipt.ticket().id())
                            .with(user("synthetic-agent").roles("AGENT")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.job.status").value("FAILED"))
                    .andExpect(jsonPath("$.job.failureCode").value(code.name()))
                    .andExpect(jsonPath("$.suggestion").value(org.hamcrest.Matchers.nullValue()));
        }
        mvc.perform(get("/api/tickets/{id}/ai-suggestion", receipt.ticket().id()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tickets/{id}/ai-suggestion", receipt.ticket().id())
                        .with(user("synthetic-user").roles("USER")))
                .andExpect(status().isForbidden());
        assertThat(providerCalls).hasValue(0);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void generic_failure_entry_point_cannot_bypass_the_privacy_recovery_block() {
        for (AiJobFailureCode code : List.of(AiJobFailureCode.PRIVACY_SCAN_TIMEOUT,
                AiJobFailureCode.PRIVACY_SCAN_INVALID_RESULT)) {
            TicketReceiptResult receipt = receive();
            assertThat(claims.failIfCurrent(claims.claimNextPending().orElseThrow(), code)).isTrue();
            assertThat(jdbc.queryForObject("SELECT result_code FROM ai_suggestion_attempts WHERE job_id = ?",
                    String.class, receipt.jobId())).isEqualTo("AUTO_RETRY_BLOCKED");
        }
    }

    @Test
    void stale_attempt_cannot_mark_a_new_owner_failed_or_change_either_reservation() {
        TicketReceiptResult receipt = receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 hour' WHERE id = ?",
                receipt.jobId());
        assertThat(results.findStoredResult(receipt.jobId()).orElseThrow().suggestion()).isEmpty();
        AiJobClaim second = claims.claimRecoveryAfterResultCheck(receipt.jobId(), first.attemptNumber()).orElseThrow();
        assertThat(second.attemptNumber()).isEqualTo(2);
        var before = snapshot();

        assertThat(claims.failPrivacyScanIfCurrent(first, AiJobFailureCode.PRIVACY_SCAN_TIMEOUT)).isFalse();

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void repeated_failure_does_not_overwrite_the_original_terminal_reason() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThat(claims.failPrivacyScanIfCurrent(claim, AiJobFailureCode.PRIVACY_SCAN_TIMEOUT)).isTrue();
        var before = snapshot();
        assertThat(claims.failPrivacyScanIfCurrent(claim, AiJobFailureCode.PRIVACY_SCAN_INVALID_RESULT)).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void a_completed_suggestion_is_not_replaced_by_a_late_scan_failure() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        results.complete(claim, new AiSuggestionOutputValidator(200).validate("""
                {"decision":"SUGGEST","summary":"Synthetic login recovery question",
                 "categories":["ACCOUNT"],"priority":"NORMAL"}
                """));
        var before = snapshot();
        assertThat(claims.failPrivacyScanIfCurrent(claim, AiJobFailureCode.PRIVACY_SCAN_TIMEOUT)).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void job_write_failure_rolls_back_the_attempt_classification_not_the_receipt() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        var before = snapshot();
        // Testcontainer-only fault: reject the second update after the attempt update succeeded.
        jdbc.execute("ALTER TABLE ai_suggestion_jobs ADD CONSTRAINT privacy_failure_test_rejection "
                + "CHECK (last_failure_code IS DISTINCT FROM 'PRIVACY_SCAN_TIMEOUT')");
        try {
            assertThatThrownBy(() -> claims.failPrivacyScanIfCurrent(claim, AiJobFailureCode.PRIVACY_SCAN_TIMEOUT))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE ai_suggestion_jobs DROP CONSTRAINT privacy_failure_test_rejection");
        }
    }

    @Test
    void the_scan_specific_entry_point_rejects_a_provider_failure_code_without_writes() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        var before = snapshot();
        assertThatThrownBy(() -> claims.failPrivacyScanIfCurrent(claim, AiJobFailureCode.PROVIDER_REFUSED))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_PRIVACY_FAILURE_CODE_INVALID");
        assertThat(snapshot()).isEqualTo(before);
    }

    private TicketReceiptResult receive() {
        return receipts.receive("Synthetic privacy test", ORIGINAL, lab.helpdesk.identity.IdentityFixtures.actor("synthetic-author"));
    }

    private List<String> snapshot() {
        // Return comparison digests, not original message or username values in failure output.
        return TABLES.stream().map(table -> jdbc.queryForObject(
                "SELECT md5(COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text)::text, '[]')) FROM "
                        + table + " r", String.class)).toList();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestUsers {
        @Bean
        UserDetailsService privacyTestUsers(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(User.withUsername("synthetic-test-account")
                    .password(encoder.encode(UUID.randomUUID().toString())).roles("USER").build());
        }
    }
}
