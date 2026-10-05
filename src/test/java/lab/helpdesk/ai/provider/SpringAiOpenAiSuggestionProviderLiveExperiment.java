package lab.helpdesk.ai.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.JdbcAiSuggestionInputRepository;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.processing.AiSuggestionJobProcessor;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in one-call experiment, excluded from Surefire's default *Test naming patterns. */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.main.banner-mode=off"})
@ActiveProfiles("postgres")
@Testcontainers
@EnabledIfEnvironmentVariable(named = "HELPDESK_AI_LIVE_CONFIRMED", matches = "true")
class SpringAiOpenAiSuggestionProviderLiveExperiment {

    @Container
    @ServiceConnection
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.6-alpine");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String BODY = "제 계정에서 로그인 링크가 만료됐습니다. 새 링크로 로그인에는 성공했습니다. "
            + "급한 문의는 아니며 만료 이유를 알고 싶습니다. 이메일 <합성_이메일>.";

    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private AiSuggestionJobClaimService claims;
    @Autowired private JdbcAiSuggestionInputRepository inputs;
    @Autowired private AiSuggestionResultService results;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void one_live_suggestion_uses_the_committed_job_and_is_stored_in_isolated_postgres() {
        requireReservedDailyBudget();
        String key = System.getenv("HELPDESK_OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("HELPDESK_SCOPED_KEY_REQUIRED");
        }
        Boolean isolated = jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated) || TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LIVE_EXPERIMENT_REQUIRES_ISOLATED_DATABASE_AND_NO_OUTER_TRANSACTION");
        }
        var privacy = new AiInputPrivacyGuard(List.of(
                new SensitiveFragment("<합성_이메일>", SensitiveType.EMAIL)));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evidence", "LIVE_JAVA_SPRING_AI_POSTGRES");
        report.put("caseId", "SYNTHETIC_LOGIN_RECOVERY");
        report.put("protocol", "chat-completions");
        report.put("manualContentReview", "NOT_SCORED");
        report.put("browserE2e", false);
        TicketReceiptResult receipt = null;
        try (var provider = new SpringAiOpenAiSuggestionProvider(key, privacy, 200)) {
            try {
                receipt = receipts.receive("로그인 링크 만료 이유 문의", BODY, "synthetic-live-author");
                var processor = new AiSuggestionJobProcessor(claims, inputs, privacy, provider,
                        new AiSuggestionOutputValidator(200), results, Clock.systemUTC());
                AiSuggestionProcessingResult processed = processor.processNextPending();
                report.put("outcome", processed.outcome().name());
                if (processed.outcome() != AiSuggestionProcessingResult.Outcome.STORED) {
                    throw new IllegalStateException("LIVE_SUGGESTION_NOT_STORED");
                }
            } catch (AiProviderFailureException exception) {
                report.put("outcome", "LIVE_FAILED");
                report.put("providerFailureKind", exception.kind().name());
                // No Provider response, exception message or cause is printed.
                throw new IllegalStateException("LIVE_PROVIDER_FAILURE");
            } catch (RuntimeException exception) {
                report.putIfAbsent("outcome", "LIVE_FAILED");
                throw new IllegalStateException("LIVE_EXPERIMENT_FAILED");
            } finally {
                AiProviderCallEvidence call = provider.lastCallEvidence();
                report.put("httpAttempts", call.httpAttempts());
                report.put("httpStatus", call.httpStatus());
                report.put("usage", call.usage());
                report.put("expectedTariffConfirmed", call.expectedTariffConfirmed());
                if (receipt != null) {
                    inspectCommittedRows(receipt, report);
                }
                // Metadata only: no prompt, original body, summary, API key or Cookie.
                System.out.println("HELPDESK_JAVA_LIVE_EVIDENCE " + MAPPER.writeValueAsString(report));
            }
        }
        if (!Boolean.TRUE.equals(report.get("originalPreserved"))
                || !Boolean.TRUE.equals(report.get("jobSucceeded"))
                || !Long.valueOf(1).equals(report.get("suggestionCount"))) {
            throw new IllegalStateException("LIVE_RESULT_DATABASE_CHECK_FAILED");
        }
    }

    private void inspectCommittedRows(TicketReceiptResult receipt, Map<String, Object> report) {
        try {
            report.put("originalPreserved", BODY.equals(jdbc.queryForObject(
                    "SELECT body FROM ticket_messages WHERE id = ?", String.class, receipt.messageId())));
            report.put("reservedGenerationCount", jdbc.queryForObject(
                    "SELECT reserved_generation_count FROM ai_suggestion_jobs WHERE id = ?",
                    Integer.class, receipt.jobId()));
            report.put("suggestionCount", jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ticket_suggestions WHERE job_id = ?", Long.class, receipt.jobId()));
            var stored = results.findStoredResult(receipt.jobId()).orElseThrow();
            report.put("jobSucceeded", stored.job().status() == lab.helpdesk.ai.job.AiJobStatus.SUCCEEDED);
            report.put("categoryCount", stored.suggestion().map(value -> value.categories().size()).orElse(0));
        } catch (RuntimeException exception) {
            report.put("databaseInspectionFailed", true);
        }
    }

    private static void requireReservedDailyBudget() {
        try {
            String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
            if (!today.equals(System.getenv("HELPDESK_AI_LIVE_DAY"))) {
                throw new IllegalArgumentException();
            }
            Path file = Path.of("local", "ai-experiments", today + "-budget.json");
            JsonNode ledger = MAPPER.readTree(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
            if (!Files.exists(Path.of(file + ".lock")) || ledger.path("version").asInt() != 1
                    || !today.equals(ledger.path("day").asString()) || ledger.path("limitUsd").asDouble() != 1
                    || ledger.path("blocked").asBoolean() || ledger.get("pendingReservationUsd") == null
                    || ledger.get("pendingReservationUsd").isNull()
                    || ledger.get("pendingReservationUsd").asDouble() < 0.009004
                    || ledger.path("usedEstimatedUsd").asDouble() + ledger.path("heldEstimatedUsd").asDouble()
                            + ledger.get("pendingReservationUsd").asDouble() > 1) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("LIVE_DAILY_BUDGET_RESERVATION_REQUIRED");
        }
    }
}
