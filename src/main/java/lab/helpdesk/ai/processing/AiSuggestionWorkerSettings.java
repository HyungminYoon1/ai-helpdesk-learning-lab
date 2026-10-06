package lab.helpdesk.ai.processing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("helpdesk.ai.worker")
public record AiSuggestionWorkerSettings(
        @DefaultValue("1000") long pollDelayMs,
        @DefaultValue("3") int maxStorageAttempts,
        @DefaultValue("5000") long storageRetryDelayMs) {

    public AiSuggestionWorkerSettings(long pollDelayMs) {
        this(pollDelayMs, 3, 5000);
    }

    @ConstructorBinding
    public AiSuggestionWorkerSettings {
        if (pollDelayMs <= 0) {
            throw new IllegalArgumentException("AI_WORKER_POLL_DELAY_INVALID");
        }
        if (maxStorageAttempts < 1 || storageRetryDelayMs <= 0) {
            throw new IllegalArgumentException("AI_WORKER_STORAGE_RETRY_INVALID");
        }
    }
}
