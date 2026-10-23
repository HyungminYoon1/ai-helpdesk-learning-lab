package lab.helpdesk.identity;

import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.UserDetails;

public final class ConfiguredAccountIdentity {
    private ConfiguredAccountIdentity() { }

    public static UserDetails principal(UserDetails user, Environment environment, String prefix) {
        String key = environment.getProperty(prefix + ".account-key");
        if (key == null) return user; // Legacy login works, new identity-required writes fail closed.
        try {
            UUID parsed = UUID.fromString(key);
            if (!parsed.toString().equals(key)) throw new IllegalArgumentException();
            return new AccountUserDetails(user, parsed);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("HELPDESK_ACCOUNT_KEY_INVALID");
        }
    }
}
