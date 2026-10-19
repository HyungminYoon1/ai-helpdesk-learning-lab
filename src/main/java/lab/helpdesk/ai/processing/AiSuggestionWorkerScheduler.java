package lab.helpdesk.ai.processing;

import java.util.Objects;

import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class AiSuggestionWorkerScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiSuggestionWorkerScheduler.class);
    private final AiSuggestionJobWorker worker;

    public AiSuggestionWorkerScheduler(AiSuggestionJobWorker worker) {
        this.worker = Objects.requireNonNull(worker);
    }

    @Scheduled(fixedDelayString = "${helpdesk.ai.worker.poll-delay-ms:1000}",
            initialDelayString = "${helpdesk.ai.worker.poll-delay-ms:1000}")
    public void poll() {
        long startedAt = System.nanoTime();
        try {
            var result = worker.runOnce();
            // Polling is frequent: do not record idle/wait ticks as useful work.
            if (result.outcome() != Outcome.NO_JOB && result.outcome() != Outcome.STORAGE_RETRY_WAITING) {
                var claim = result.claim();
                if (claim != null) {
                    // A tick outcome is not necessarily a final Job state. Never stringify its output/claim.
                    LOGGER.info("event=AI_WORKER_TICK_RESULT jobId={} attempt={} outcome={} tickElapsedNanos={}",
                            claim.jobId(), claim.attemptNumber(), result.outcome().name(),
                            System.nanoTime() - startedAt);
                } else {
                    LOGGER.info("event=AI_WORKER_TICK_RESULT outcome={} tickElapsedNanos={}",
                            result.outcome().name(), System.nanoTime() - startedAt);
                }
            }
        } catch (RuntimeException exception) {
            // DB/Adapter failures can carry rows or input in their messages and causes.
            // Leave committed state intact; a later tick is not permission to replay RUNNING.
            LOGGER.warn("AI_WORKER_TICK_FAILED");
        }
    }
}
