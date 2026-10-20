package lab.helpdesk.web;

import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class HandlerTimingInterceptorTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(HandlerTimingInterceptor.class);
    private Level previousLevel;

    @BeforeEach
    void enable_info_for_this_logger_without_depending_on_spring_test_order() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void restore_the_previous_logger_level() {
        logger.setLevel(previousLevel);
    }

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

    @Test
    void completion_logs_only_the_server_id_handler_status_and_duration() {
        var interceptor = new HandlerTimingInterceptor();
        var request = new MockHttpServletRequest();
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(RequestIdFilter.ATTRIBUTE_NAME, requestId);
        request.setQueryString("token=UNSAFE_QUERY_MARKER");
        request.setContent("UNSAFE_BODY_MARKER".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader("Cookie", "JSESSIONID=UNSAFE_COOKIE_MARKER");
        request.addHeader("X-CSRF-TOKEN", "UNSAFE_CSRF_MARKER");
        request.addHeader("Authorization", "Bearer UNSAFE_AUTH_MARKER");
        var response = new MockHttpServletResponse();
        response.setStatus(201);
        var handler = new Object();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            interceptor.preHandle(request, response, handler);
            interceptor.afterCompletion(request, response, handler,
                    new IllegalStateException("UNSAFE_EXCEPTION_MARKER"));
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .startsWith("event=HANDLER_COMPLETED requestId=" + requestId
                                + " handler=Object status=201 elapsedNanos=")
                        .matches(".*elapsedNanos=\\d+")
                        .doesNotContain("UNSAFE_");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void completion_respects_a_warn_level_instead_of_forcing_info_output() {
        logger.setLevel(Level.WARN);
        var interceptor = new HandlerTimingInterceptor();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var handler = new Object();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            interceptor.preHandle(request, response, handler);
            interceptor.afterCompletion(request, response, handler, null);
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
