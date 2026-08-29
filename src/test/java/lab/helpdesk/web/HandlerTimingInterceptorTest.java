package lab.helpdesk.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerTimingInterceptorTest {

    @Test
    void pre_handle_stores_start_time_and_allows_handler() {

        // Given
        var interceptor = new HandlerTimingInterceptor();

        var request = new MockHttpServletRequest();

        var response = new MockHttpServletResponse();

        var handler = new Object();

        // When
        boolean canContinue = interceptor.preHandle(
                request,
                response,
                handler);

        // Then
        assertTrue(canContinue);

        assertInstanceOf(
                Long.class,
                request.getAttribute(
                        HandlerTimingInterceptor.STARTED_AT_ATTRIBUTE));
    }
}
