package lab.helpdesk.cognito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lab.helpdesk.identity.AccountUserDetails;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

final class CognitoLogin {
    private final CognitoAccounts accounts;
    final HttpSessionCsrfTokenRepository csrf = new HttpSessionCsrfTokenRepository();
    final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository() {
        @Override
        public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
            // The provider principal must never become a browser's Helpdesk session.
            if (!(context.getAuthentication() instanceof OAuth2AuthenticationToken)) {
                super.saveContext(context, request, response);
            }
        }
    };

    CognitoLogin(CognitoAccounts accounts) {
        this.accounts = accounts;
    }

    void clear(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    }

    void promote(JdbcCognitoAccounts.Account account, HttpServletRequest request, HttpServletResponse response) {
        request.changeSessionId();
        csrf.saveToken(null, request, response);
        var context = contextFor(account);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    OncePerRequestFilter checkAccount(RequestMatcher accountRequests) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof AccountUserDetails principal
                        && accountRequests.matches(request)) {
                    try {
                        var account = accounts.find(principal.accountKey())
                                .filter(current -> current.status() == AccountStatus.ACTIVE
                                        && Objects.equals(principal.sessionVersion(), current.sessionVersion()));
                        if (account.isEmpty()) {
                            clear(request, response);
                        } else {
                            SecurityContextHolder.setContext(contextFor(account.get()));
                        }
                    } catch (DataAccessException ex) {
                        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }

    private static SecurityContext contextFor(JdbcCognitoAccounts.Account account) {
        List<SimpleGrantedAuthority> grants = new ArrayList<>();
        grants.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (account.agent()) grants.add(new SimpleGrantedAuthority("ROLE_AGENT"));
        if (account.accountAdmin()) grants.add(new SimpleGrantedAuthority("ROLE_ACCOUNT_ADMIN"));

        var user = User.withUsername(account.username()).password("").authorities(grants).build();
        var principal = new AccountUserDetails(user, account.accountKey(), account.sessionVersion());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, grants));
        return context;
    }
}
