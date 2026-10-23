package lab.helpdesk.identity;

import java.util.UUID;

/** Server-authenticated identity reference; never populated from an HTTP body. */
public record AuthenticatedActor(UUID accountKey, String username, boolean agent) {
    public AuthenticatedActor {
        if (accountKey == null || username == null || username.isBlank()) {
            throw new IllegalArgumentException("authenticated actor is required");
        }
    }

    @Override
    public String toString() {
        return "AuthenticatedActor[redacted]";
    }
}
