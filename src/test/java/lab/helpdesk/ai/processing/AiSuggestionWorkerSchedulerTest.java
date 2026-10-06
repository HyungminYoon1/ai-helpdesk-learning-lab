package lab.helpdesk.ai.processing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSuggestionWorkerSchedulerTest {

    @Test
    void the_scheduler_calls_one_tick_without_adding_its_own_retry_loop() {
        AiSuggestionJobWorker worker = mock(AiSuggestionJobWorker.class);
        new AiSuggestionWorkerScheduler(worker).poll();
        verify(worker).runOnce();
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
}
