package lab.helpdesk.responsetime.strategy;

import java.time.Duration;

public final class UrgentResponseTimePolicy
        implements ResponseTimePolicy {

    @Override
    public Duration targetResponseTime() {
        return Duration.ofHours(4);
    }
}
