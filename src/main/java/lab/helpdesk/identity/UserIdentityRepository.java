package lab.helpdesk.identity;

import java.util.OptionalLong;
import java.util.UUID;

public interface UserIdentityRepository {
    OptionalLong findId(UUID accountKey);

    /** Trusted explicit provisioning, not an HTTP endpoint or login-time name fallback. */
    long bind(UUID accountKey, String loginUsername);

    default long requireId(AuthenticatedActor actor) {
        if (actor == null) {
            throw new IdentityNotLinkedException();
        }
        return findId(actor.accountKey()).orElseThrow(IdentityNotLinkedException::new);
    }
}
