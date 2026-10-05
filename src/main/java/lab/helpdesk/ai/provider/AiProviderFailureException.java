package lab.helpdesk.ai.provider;

import java.util.Objects;

/** No response body, credential, prompt, parser message, or transport cause is retained. */
public final class AiProviderFailureException extends RuntimeException {

    private final Kind kind;

    public AiProviderFailureException(Kind kind) {
        super("AI_PROVIDER_" + Objects.requireNonNull(kind).name());
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        REFUSED, OUTCOME_UNKNOWN, CONFIGURATION
    }
}
