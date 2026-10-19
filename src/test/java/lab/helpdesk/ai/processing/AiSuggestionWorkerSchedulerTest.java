package lab.helpdesk.ai.processing;

import java.time.Instant;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.job.AiJobPolicy;
import lab.helpdesk.ai.job.AiJobRequestKind;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSuggestionWorkerSchedulerTest {

    @Test
    void the_scheduler_calls_one_tick_without_adding_its_own_retry_loop() {
        AiSuggestionJobWorker worker = mock(AiSuggestionJobWorker.class);
        when(worker.runOnce()).thenReturn(new AiSuggestionProcessingResult(Outcome.NO_JOB, null, null));
        new AiSuggestionWorkerScheduler(worker).poll();
        verify(worker).runOnce();
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"NO_JOB", "STORAGE_RETRY_WAITING"},
            mode = EnumSource.Mode.EXCLUDE)
    void tick_outcomes_are_correlated_without_logging_the_claim_or_output(Outcome outcome) {
        AiSuggestionJobWorker worker = mock(AiSuggestionJobWorker.class);
        var output = mock(ContractValidatedOutput.class);
        when(output.toString()).thenReturn("UNSAFE_VALIDATED_OUTPUT_MARKER");
        when(worker.runOnce()).thenReturn(new AiSuggestionProcessingResult(outcome, claim(), output));
        Logger logger = (Logger) LoggerFactory.getLogger(AiSuggestionWorkerScheduler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new AiSuggestionWorkerScheduler(worker).poll();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .startsWith("event=AI_WORKER_TICK_RESULT jobId=90 attempt=2 outcome="
                                + outcome.name() + " tickElapsedNanos=")
                        .matches(".*tickElapsedNanos=\\d+")
                        .doesNotContain("UNSAFE_", "inputMessageId", "policyVersion");
                assertThat(event.getThrowableProxy()).isNull();
            });
            verify(worker).runOnce();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"NO_JOB", "STORAGE_RETRY_WAITING"})
    void idle_and_storage_wait_ticks_do_not_flood_the_log(Outcome outcome) {
        AiSuggestionJobWorker worker = mock(AiSuggestionJobWorker.class);
        when(worker.runOnce()).thenReturn(new AiSuggestionProcessingResult(outcome,
                outcome == Outcome.NO_JOB ? null : claim(), null));
        Logger logger = (Logger) LoggerFactory.getLogger(AiSuggestionWorkerScheduler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new AiSuggestionWorkerScheduler(worker).poll();
            assertThat(appender.list).isEmpty();
            verify(worker).runOnce();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void an_unconfirmed_recovery_without_a_claim_does_not_invent_an_identity() {
        AiSuggestionJobWorker worker = mock(AiSuggestionJobWorker.class);
        when(worker.runOnce()).thenReturn(new AiSuggestionProcessingResult(
                Outcome.RECOVERY_STATE_UNCONFIRMED, null, null));
        Logger logger = (Logger) LoggerFactory.getLogger(AiSuggestionWorkerScheduler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new AiSuggestionWorkerScheduler(worker).poll();
            assertThat(appender.list).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage())
                            .startsWith("event=AI_WORKER_TICK_RESULT outcome=RECOVERY_STATE_UNCONFIRMED ")
                            .doesNotContain("jobId=", "attempt="));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void tick_failures_log_only_a_fixed_code_without_the_cause_or_message() {
        AiSuggestionJobWorker worker = mock(AiSuggestionJobWorker.class);
        when(worker.runOnce()).thenThrow(new IllegalStateException("UNSAFE_INPUT_MARKER",
                new IllegalArgumentException("UNSAFE_CAUSE_MARKER")));
        Logger logger = (Logger) LoggerFactory.getLogger(AiSuggestionWorkerScheduler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new AiSuggestionWorkerScheduler(worker).poll();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("AI_WORKER_TICK_FAILED");
                assertThat(event.getThrowableProxy()).isNull();
            });
            verify(worker).runOnce();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private AiJobClaim claim() {
        Instant started = Instant.parse("2026-10-19T00:00:00Z");
        return new AiJobClaim(90, 31, 2, 2, 0, AiJobRequestKind.RECOVERY,
                new AiJobPolicy("UNSAFE_POLICY_MARKER", 3, 1, 60000, 120000, 5000, 300000),
                started, started.plusSeconds(300), started.plusSeconds(120));
    }
}
