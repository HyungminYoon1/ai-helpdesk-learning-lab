package lab.helpdesk.ai.processing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("helpdesk.ai.worker")
public record AiSuggestionWorkerSettings(@DefaultValue("1000") long pollDelayMs) {

    public AiSuggestionWorkerSettings {
        if (pollDelayMs <= 0) {
            throw new IllegalArgumentException("AI_WORKER_POLL_DELAY_INVALID");
        }
    }
}
