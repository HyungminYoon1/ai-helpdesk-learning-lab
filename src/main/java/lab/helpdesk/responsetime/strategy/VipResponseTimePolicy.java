package lab.helpdesk.responsetime.strategy;

import java.time.Duration;

public final class VipResponseTimePolicy
        implements ResponseTimePolicy {

    @Override
    public Duration targetResponseTime() {
        return Duration.ofHours(1);
    }
}
