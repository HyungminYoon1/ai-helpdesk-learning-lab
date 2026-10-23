package lab.helpdesk.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("postgres")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConfiguredIdentityBootstrap implements ApplicationRunner {
    private final UserIdentityRepository identities;
    private final Environment environment;
    private final ObjectProvider<UserDetailsService> users;

    public ConfiguredIdentityBootstrap(UserIdentityRepository identities, Environment environment,
            ObjectProvider<UserDetailsService> users) {
        this.identities = identities;
        this.environment = environment;
        this.users = users;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        String mode = environment.acceptsProfiles(Profiles.of("deployment")) ? "deployment" : "local";
        if (!environment.acceptsProfiles(Profiles.of("deployment", "local-browser"))) return;
        for (String role : new String[] {"user", "agent"}) {
            String prefix = "helpdesk." + mode + "." + role;
            if (!environment.containsProperty(prefix + ".account-key")) continue;
            String name = environment.getRequiredProperty(prefix + ".username");
            var principal = users.getObject().loadUserByUsername(name);
            if (!(principal instanceof AccountUserDetails account)) {
                throw new IllegalStateException("HELPDESK_IDENTITY_PRINCIPAL_REQUIRED");
            }
            identities.bind(account.accountKey(), account.getUsername());
        }
    }
}
