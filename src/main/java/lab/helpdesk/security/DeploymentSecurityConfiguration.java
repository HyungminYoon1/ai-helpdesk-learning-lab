package lab.helpdesk.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import lab.helpdesk.identity.IdentityAwareInMemoryUserDetailsManager;
import lab.helpdesk.identity.ConfiguredAccountIdentity;

/** Explicit learning-deployment accounts; no registration or persistent user management. */
@Configuration(proxyBeanMethods = false)
@Profile("deployment")
public class DeploymentSecurityConfiguration {

    @Bean
    UserDetailsService deploymentUserDetailsService(PasswordEncoder encoder, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("local-browser"))) {
            throw new IllegalStateException("HELPDESK_DEPLOYMENT_LOCAL_BROWSER_PROFILE_CONFLICT");
        }
        if (environment.acceptsProfiles(Profiles.of("in-memory"))) {
            throw new IllegalStateException("HELPDESK_DEPLOYMENT_IN_MEMORY_PROFILE_CONFLICT");
        }
        if (!environment.acceptsProfiles(Profiles.of("postgres"))) {
            throw new IllegalStateException("HELPDESK_DEPLOYMENT_POSTGRES_PROFILE_REQUIRED");
        }

        String userName = required(environment, "helpdesk.deployment.user.username",
                "HELPDESK_DEPLOYMENT_USER_USERNAME_REQUIRED");
        String userPassword = required(environment, "helpdesk.deployment.user.password",
                "HELPDESK_DEPLOYMENT_USER_PASSWORD_REQUIRED");
        String agentName = required(environment, "helpdesk.deployment.agent.username",
                "HELPDESK_DEPLOYMENT_AGENT_USERNAME_REQUIRED");
        String agentPassword = required(environment, "helpdesk.deployment.agent.password",
                "HELPDESK_DEPLOYMENT_AGENT_PASSWORD_REQUIRED");
        if (userName.equalsIgnoreCase(agentName)) {
            throw new IllegalStateException("HELPDESK_DEPLOYMENT_USERNAMES_MUST_BE_DISTINCT");
        }

        return new IdentityAwareInMemoryUserDetailsManager(
                ConfiguredAccountIdentity.principal(
                        User.withUsername(userName).password(encoder.encode(userPassword)).roles("USER").build(),
                        environment, "helpdesk.deployment.user"),
                ConfiguredAccountIdentity.principal(
                        User.withUsername(agentName).password(encoder.encode(agentPassword))
                                .roles("USER", "AGENT").build(), environment, "helpdesk.deployment.agent"));
    }

    private static String required(Environment environment, String property, String failureCode) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            // Keep property values and credentials out of startup errors.
            throw new IllegalStateException(failureCode);
        }
        return value;
    }
}
