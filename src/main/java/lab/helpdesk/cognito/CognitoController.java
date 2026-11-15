package lab.helpdesk.cognito;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lab.helpdesk.identity.AuthenticatedActorFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

@RestController
@Profile("cognito")
class CognitoController {
    private final CognitoOnboarding onboarding;
    private final CognitoAccounts accounts;
    private final CognitoLogin login;
    private final CognitoWebSecurity.LoginConfigurer security;

    CognitoController(CognitoOnboarding onboarding, CognitoAccounts accounts, CognitoLogin login,
            CognitoWebSecurity.LoginConfigurer security) {
        this.onboarding = onboarding;
        this.accounts = accounts;
        this.login = login;
        this.security = security;
    }

    @GetMapping("/login")
    void loginPage(HttpServletResponse response) throws IOException {
        response.sendRedirect("/cognito.html");
    }

    @GetMapping("/onboarding/state")
    ResponseEntity<State> state(CsrfToken csrf, HttpServletRequest request) {
        return response(HttpStatus.OK,
                new State(onboarding.step(request.getSession(false)).name(), csrf.getParameterName(), csrf.getToken()));
    }

    @PostMapping("/onboarding/start")
    void start(@RequestParam(required = false) String invite, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        HttpSession session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            onboarding.begin(session, invite);
            login.clear(request, response);
            response.sendRedirect(CognitoSettings.AUTHORIZE_PATH);
        }
    }

    @PostMapping("/onboarding/email-code")
    ResponseEntity<Void> sendEmail(HttpSession session) {
        onboarding.requestEmailCode(session);
        return response(HttpStatus.NO_CONTENT, null);
    }

    @PostMapping("/onboarding/verify-email")
    ResponseEntity<Void> verifyEmail(@RequestParam String code, HttpSession session) {
        onboarding.verifyEmail(session, code);
        return response(HttpStatus.NO_CONTENT, null);
    }

    @PostMapping("/onboarding/reauth")
    void reauth(HttpSession session, HttpServletResponse response) throws IOException {
        onboarding.prepareLogout(session);
        response.sendRedirect(security.logoutUrl("/onboarding/resume"));
    }

    @GetMapping("/onboarding/resume")
    void resume(HttpSession session, HttpServletResponse response) throws IOException {
        onboarding.resume(session);
        response.sendRedirect(CognitoSettings.AUTHORIZE_PATH);
    }

    @PostMapping("/onboarding/accept")
    void accept(HttpServletRequest request, HttpServletResponse response, HttpSession session) throws IOException {
        synchronized (WebUtils.getSessionMutex(session)) {
            var account = onboarding.accept(session);
            login.promote(account, request, response);
            response.sendRedirect("/my-tickets.html");
        }
    }

    @PostMapping("/api/account-invitations")
    ResponseEntity<CognitoAccounts.IssuedInvitation> issue(@RequestBody Issue request, Authentication authentication) {
        var issuer = AuthenticatedActorFactory.from(authentication);
        return response(HttpStatus.CREATED, accounts.issue(issuer.accountKey(), request.email(), request.role(),
                request.serviceId(), request.displayName(), Instant.now()));
    }

    @GetMapping("/api/accounts")
    ResponseEntity<List<AccountSummary>> listAccounts(Authentication authentication) {
        var administrator = AuthenticatedActorFactory.from(authentication);
        return response(HttpStatus.OK, accounts.listAccounts(administrator.accountKey()));
    }

    @PutMapping("/api/accounts/{accountKey}/status")
    ResponseEntity<CognitoAccounts.StatusResult> changeStatus(@PathVariable UUID accountKey,
            @RequestBody ChangeStatus request, Authentication authentication) {
        var administrator = AuthenticatedActorFactory.from(authentication);
        var result = accounts.changeStatus(administrator.accountKey(), accountKey, request.status(),
                request.expectedSessionVersion(), request.reason());
        return response(HttpStatus.OK, result);
    }

    @ExceptionHandler(CognitoFailure.class)
    ResponseEntity<Map<String, String>> rejected(CognitoFailure failure) {
        return response(failure.code().status, Map.of("code", failure.code().name()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Map<String, String>> storageUnavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, Map.of("code", "ACCOUNT_STORAGE_UNAVAILABLE"));
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private record State(String step, String csrfName, String csrfToken) {
        @Override public String toString() { return "CognitoState[redacted]"; }
    }

    private record Issue(String email, String role, Long serviceId, String displayName) {
        @Override public String toString() { return "IssueInvitation[redacted]"; }
    }

    private record ChangeStatus(AccountStatus status, Long expectedSessionVersion,
            CognitoAccounts.StatusChangeReason reason) { }
}
