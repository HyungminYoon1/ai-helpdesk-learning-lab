package lab.helpdesk.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class RequestIdFilter
        extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";

    public static final String ATTRIBUTE_NAME = RequestIdFilter.class.getName()
            + ".requestId";

    // Log only the canonical, server-generated ID, never a client Header or arbitrary Attribute.
    public static String requestIdForLog(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE_NAME);
        if (value instanceof String requestId) {
            try {
                String canonical = UUID.fromString(requestId).toString();
                if (canonical.equals(requestId)) {
                    return canonical;
                }
            } catch (IllegalArgumentException ignored) {
                // A missing/invalid diagnostic ID must not change the HTTP result.
            }
        }
        return "unavailable";
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = UUID.randomUUID().toString();

        request.setAttribute(
                ATTRIBUTE_NAME,
                requestId);

        response.setHeader(
                HEADER_NAME,
                requestId);

        filterChain.doFilter(
                request,
                response);
    }
}
