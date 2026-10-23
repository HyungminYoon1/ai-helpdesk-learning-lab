package lab.helpdesk.identity;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** Retains the configured identity while keeping ordinary in-memory credential verification. */
public final class IdentityAwareInMemoryUserDetailsManager extends InMemoryUserDetailsManager {
    private final Map<String, UUID> keys;

    public IdentityAwareInMemoryUserDetailsManager(UserDetails... configuredUsers) {
        super(Arrays.stream(configuredUsers).map(user -> User.withUserDetails(user).build()).toList());
        Map<String, UUID> configuredKeys = new HashMap<>();
        var distinctKeys = new HashSet<UUID>();
        for (UserDetails user : configuredUsers) {
            if (user instanceof AccountUserDetails account) {
                if (!distinctKeys.add(account.accountKey())) {
                    throw new IllegalArgumentException("ACCOUNT_IDENTITY_KEY_REUSED");
                }
                configuredKeys.put(user.getUsername().toLowerCase(Locale.ROOT), account.accountKey());
            }
        }
        keys = Map.copyOf(configuredKeys);
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        // A fresh credential copy is erased after authentication, not the manager's stored hash.
        UserDetails user = User.withUserDetails(super.loadUserByUsername(username)).build();
        UUID key = keys.get(user.getUsername().toLowerCase(Locale.ROOT));
        return key == null ? user : new AccountUserDetails(user, key);
    }
}
