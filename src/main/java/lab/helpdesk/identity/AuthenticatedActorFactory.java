package lab.helpdesk.identity;

import org.springframework.security.core.Authentication;

public final class AuthenticatedActorFactory {
    private AuthenticatedActorFactory() { }

    public static AuthenticatedActor from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountUserDetails principal)) {
            throw new IdentityNotLinkedException();
        }
        boolean agent = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_AGENT"));
        return new AuthenticatedActor(principal.accountKey(), principal.getUsername(), agent);
    }
}
