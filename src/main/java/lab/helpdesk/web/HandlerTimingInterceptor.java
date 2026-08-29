package lab.helpdesk.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public final class HandlerTimingInterceptor
        implements HandlerInterceptor {

    public static final String STARTED_AT_ATTRIBUTE = HandlerTimingInterceptor.class.getName()
            + ".startedAt";

    private static final Logger logger = LoggerFactory.getLogger(
            HandlerTimingInterceptor.class);

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {

        request.setAttribute(
                STARTED_AT_ATTRIBUTE,
                System.nanoTime());

        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {

        Object startedAtValue = request.getAttribute(
                STARTED_AT_ATTRIBUTE);

        if (!(startedAtValue instanceof Long startedAt)) {
            return;
        }

        long elapsedNanos = System.nanoTime() - startedAt;

        String handlerName = handler instanceof HandlerMethod handlerMethod
                ? handlerMethod.getBeanType().getSimpleName()
                        + "#"
                        + handlerMethod.getMethod().getName()
                : handler.getClass().getSimpleName();

        logger.info(
                "handler={} status={} elapsedNanos={}",
                handlerName,
                response.getStatus(),
                elapsedNanos);
    }
}
