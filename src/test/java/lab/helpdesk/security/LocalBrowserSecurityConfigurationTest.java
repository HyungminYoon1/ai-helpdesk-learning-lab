package lab.helpdesk.security;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LocalBrowserSecurityConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(context -> {
                        context.getEnvironment()
                                .getPropertySources()
                                .remove(StandardEnvironment
                                        .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                        context.getEnvironment()
                                .getPropertySources()
                                .remove(StandardEnvironment
                                        .SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                        context.getEnvironment()
                                .setActiveProfiles("local-browser");
                    })
                    .withUserConfiguration(
                            LocalBrowserSecurityConfiguration.class)
                    .withBean(
                            PasswordEncoder.class,
                            BCryptPasswordEncoder::new);

    @Test
    void local_browser_profile_creates_user_and_agent_from_external_properties() {
        String userUsername = UUID.randomUUID().toString();
        String userPassword = UUID.randomUUID().toString();
        String agentUsername = UUID.randomUUID().toString();
        String agentPassword = UUID.randomUUID().toString();

        contextRunner
                .withPropertyValues(
                        "helpdesk.local.user.username=" + userUsername,
                        "helpdesk.local.user.password=" + userPassword,
                        "helpdesk.local.agent.username=" + agentUsername,
                        "helpdesk.local.agent.password=" + agentPassword)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(UserDetailsService.class);

                    UserDetailsService users = context.getBean(
                            UserDetailsService.class);
                    PasswordEncoder encoder = context.getBean(
                            PasswordEncoder.class);

                    var user = users.loadUserByUsername(userUsername);
                    var agent = users.loadUserByUsername(agentUsername);

                    assertThat(user.getAuthorities())
                            .extracting(GrantedAuthority::getAuthority)
                            .containsExactly("ROLE_USER");
                    assertThat(agent.getAuthorities())
                            .extracting(GrantedAuthority::getAuthority)
                            .containsExactlyInAnyOrder(
                                    "ROLE_USER",
                                    "ROLE_AGENT");
                    assertThat(encoder.matches(
                            userPassword,
                            user.getPassword()))
                            .isTrue();
                    assertThat(encoder.matches(
                            agentPassword,
                            agent.getPassword()))
                            .isTrue();
                });
    }

    @Test
    void local_browser_profile_fails_when_credentials_are_missing() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining(
                            "helpdesk.local.user.username");
        });
    }

    @Test
    void local_browser_profile_fails_when_a_credential_is_blank() {
        String userUsername = UUID.randomUUID().toString();
        String agentUsername = UUID.randomUUID().toString();
        String agentPassword = UUID.randomUUID().toString();

        contextRunner
                .withPropertyValues(
                        "helpdesk.local.user.username=" + userUsername,
                        "helpdesk.local.user.password=",
                        "helpdesk.local.agent.username=" + agentUsername,
                        "helpdesk.local.agent.password=" + agentPassword)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "helpdesk.local.user.password must not be blank");
                });
    }
}
