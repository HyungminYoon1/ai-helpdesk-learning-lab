package lab.helpdesk.responsetime.strategy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResponseTimeCalculatorTest {

    @Test
    void normal_policy_has_24_hour_target() {
        // Given
        ResponseTimePolicy policy =
                new NormalResponseTimePolicy();
        var calculator =
                new ResponseTimeCalculator(policy);

        // When
        var actualTarget =
                calculator.targetResponseTime();

        // Then
        assertEquals(Duration.ofHours(24), actualTarget);
    }

    @Test
    void urgent_policy_has_4_hour_target() {
        // Given
        ResponseTimePolicy policy =
                new UrgentResponseTimePolicy();
        var calculator =
                new ResponseTimeCalculator(policy);

        // When
        var actualTarget =
                calculator.targetResponseTime();

        // Then
        assertEquals(Duration.ofHours(4), actualTarget);
    }
}
