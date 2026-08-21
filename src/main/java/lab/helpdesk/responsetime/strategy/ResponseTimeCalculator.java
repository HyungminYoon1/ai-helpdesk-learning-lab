package lab.helpdesk.responsetime.strategy;

import java.time.Duration;
import java.util.Objects;

public final class ResponseTimeCalculator {

    private final ResponseTimePolicy policy;

    public ResponseTimeCalculator(
            ResponseTimePolicy policy) {

        this.policy = Objects.requireNonNull(
                policy,
                "policy must not be null");
    }

    public Duration targetResponseTime() {
        return policy.targetResponseTime();
    }
}
