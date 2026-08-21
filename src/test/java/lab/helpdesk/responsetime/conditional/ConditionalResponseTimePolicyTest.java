package lab.helpdesk.responsetime.conditional;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import lab.helpdesk.responsetime.TicketPriority;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConditionalResponseTimePolicyTest {

    @Test
    void normal_priority_has_24_hour_target() {
        // Given
        var policy = new ConditionalResponseTimePolicy();
        var priority = TicketPriority.NORMAL;

        // When
        var actualTarget = policy.targetResponseTime(priority);

        // Then
        assertEquals(Duration.ofHours(24), actualTarget);
    }

    @Test
    void urgent_priority_has_4_hour_target() {
        // Given
        var policy = new ConditionalResponseTimePolicy();
        var priority = TicketPriority.URGENT;

        // When
        var actualTarget = policy.targetResponseTime(priority);

        // Then
        assertEquals(Duration.ofHours(4), actualTarget);
    }
}
