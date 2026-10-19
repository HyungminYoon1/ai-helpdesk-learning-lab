package lab.helpdesk.web;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdFilterTest {

    @Test
    void request_id_is_added_to_request_and_response()
            throws Exception {

        // Given
        var filter = new RequestIdFilter();
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "UNTRUSTED_CLIENT_REQUEST_ID");
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
        assertNotEquals("UNTRUSTED_CLIENT_REQUEST_ID", requestId);
        assertEquals(UUID.fromString(requestId).toString(), requestId);
        assertEquals(requestId, RequestIdFilter.requestIdForLog(request));
        assertEquals(
                requestId,
                request.getAttribute(
                        RequestIdFilter.ATTRIBUTE_NAME));
        assertTrue(chainCalled.get());
    }

    @Test
    void unsafe_or_missing_diagnostic_attributes_are_not_formatted_into_logs() {
        var request = new MockHttpServletRequest();
        assertEquals("unavailable", RequestIdFilter.requestIdForLog(request));
        for (Object invalid : new Object[] {"UNSAFE_ATTRIBUTE\r\nFORGED_EVENT", "1-1-1-1-1",
                new Object() {
                    @Override public String toString() {
                        throw new AssertionError("arbitrary diagnostic objects must not be stringified");
                    }
                }}) {
            request.setAttribute(RequestIdFilter.ATTRIBUTE_NAME, invalid);
            assertEquals("unavailable", RequestIdFilter.requestIdForLog(request));
        }
    }
}
