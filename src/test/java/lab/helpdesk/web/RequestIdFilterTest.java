package lab.helpdesk.web;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdFilterTest {

    @Test
    void request_id_is_added_to_request_and_response()
            throws Exception {

        // Given
        var filter = new RequestIdFilter();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var chainCalled = new AtomicBoolean(false);

        // When
        filter.doFilter(
                request,
                response,
                (filteredRequest, filteredResponse) -> chainCalled.set(true));

        // Then
        String requestId = response.getHeader(
                RequestIdFilter.HEADER_NAME);

        assertNotNull(requestId);
        assertFalse(requestId.isBlank());
        assertEquals(
                requestId,
                request.getAttribute(
                        RequestIdFilter.ATTRIBUTE_NAME));
        assertTrue(chainCalled.get());
    }
}
