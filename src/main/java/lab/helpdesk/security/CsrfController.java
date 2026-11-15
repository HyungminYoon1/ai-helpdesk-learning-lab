package lab.helpdesk.security;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.env.Environment;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

@RestController
@RequestMapping("/api/csrf")
public class CsrfController {

    private static final String RECEIPT_CONTEXT_ATTRIBUTE = CsrfController.class.getName() + ".receiptContext";

    private final Environment environment;

    public CsrfController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping
    public CsrfResponse csrf(CsrfToken csrfToken, HttpServletRequest request) {
        String token = csrfToken.getToken();
        UUID receiptContext = environment.matchesProfiles("postgres")
                ? receiptContext(request.getSession()) : null;
        return new CsrfResponse(csrfToken.getHeaderName(), token, receiptContext);
    }

    private static UUID receiptContext(HttpSession session) {
        synchronized (WebUtils.getSessionMutex(session)) {
            // Authentication can change the session ID while retaining its attributes.
            String sessionId = session.getId();
            if (session.getAttribute(RECEIPT_CONTEXT_ATTRIBUTE) instanceof ReceiptContext context
                    && context.sessionId().equals(sessionId)) {
                return context.marker();
            }
            var context = new ReceiptContext(sessionId, UUID.randomUUID());
            session.setAttribute(RECEIPT_CONTEXT_ATTRIBUTE, context);
            return context.marker();
        }
    }

    private record ReceiptContext(String sessionId, UUID marker) { }

    public record CsrfResponse(String headerName, String token,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID receiptContext) { }
}
