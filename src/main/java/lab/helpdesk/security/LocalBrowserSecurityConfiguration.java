package lab.helpdesk.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration(proxyBeanMethods = false)
@Profile("local-browser")
public class LocalBrowserSecurityConfiguration {

    @Bean
    UserDetailsService localBrowserUserDetailsService(
            PasswordEncoder passwordEncoder,
            Environment environment) {

        String userUsername = getRequiredNonBlank(
                environment,
                "helpdesk.local.user.username");
        String userPassword = getRequiredNonBlank(
                environment,
                "helpdesk.local.user.password");
        String agentUsername = getRequiredNonBlank(
                environment,
                "helpdesk.local.agent.username");
        String agentPassword = getRequiredNonBlank(
                environment,
                "helpdesk.local.agent.password");

        UserDetails user = User.withUsername(userUsername)
                .password(passwordEncoder.encode(userPassword))
                .roles("USER")
                .build();
        UserDetails agent = User.withUsername(agentUsername)
                .password(passwordEncoder.encode(agentPassword))
                .roles("USER", "AGENT")
                .build();

        return new InMemoryUserDetailsManager(user, agent);
    }

    private static String getRequiredNonBlank(
            Environment environment,
            String propertyName) {

        String value = environment.getRequiredProperty(propertyName);

        if (value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " must not be blank");
        }

        return value;
    }
}
