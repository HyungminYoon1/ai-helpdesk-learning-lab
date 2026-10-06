package lab.helpdesk.ai.processing;

import java.util.Objects;

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
        try {
            worker.runOnce();
        } catch (RuntimeException exception) {
            // DB/Adapter failures can carry rows or input in their messages and causes.
            // Leave committed state intact; a later tick is not permission to replay RUNNING.
            LOGGER.warn("AI_WORKER_TICK_FAILED");
        }
    }
}
