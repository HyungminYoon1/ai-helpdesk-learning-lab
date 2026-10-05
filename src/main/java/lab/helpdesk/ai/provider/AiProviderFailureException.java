package lab.helpdesk.ai.provider;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** No response body, credential, prompt, parser message, or transport cause is retained. */
public final class AiProviderFailureException extends RuntimeException {

    private final Kind kind;
    private final Reason reason;
    private final Duration retryAfter;

    public AiProviderFailureException(Kind kind) {
        this(kind, Reason.UNSPECIFIED, null);
    }

    public AiProviderFailureException(Kind kind, Reason reason, Duration retryAfter) {
        super("AI_PROVIDER_" + Objects.requireNonNull(kind).name()
                + "_" + Objects.requireNonNull(reason).name());
        if (retryAfter != null && (retryAfter.isNegative() || kind != Kind.TEMPORARY_REJECTION)) {
            throw new IllegalArgumentException("AI_PROVIDER_RETRY_AFTER_INVALID");
        }
        this.kind = kind;
        this.reason = reason;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public Reason reason() {
        return reason;
    }

    /** A minimum wait, not permission to retry or an Adapter-side retry loop. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public enum Kind {
        REFUSED, OUTCOME_UNKNOWN, CONFIGURATION, INVALID_RESPONSE, TEMPORARY_REJECTION
    }

    public enum Reason {
        UNSPECIFIED, AUTHENTICATION, BILLING_OR_QUOTA, REQUEST_REJECTED,
        RATE_LIMIT, OVERLOADED, TRANSPORT, SERVER_ERROR, REDIRECT,
        RESPONSE_TOO_LARGE, MALFORMED_ENVELOPE, INCOMPLETE, REFUSAL
    }
}
