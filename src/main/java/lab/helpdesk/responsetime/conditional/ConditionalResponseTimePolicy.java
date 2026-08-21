package lab.helpdesk.responsetime.conditional;

import java.time.Duration;

import lab.helpdesk.responsetime.TicketPriority;

public final class ConditionalResponseTimePolicy {

    public Duration targetResponseTime(
            TicketPriority priority) {

        return switch (priority) {
            case NORMAL -> Duration.ofHours(24);
            case URGENT -> Duration.ofHours(4);
        };
    }
}
