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
