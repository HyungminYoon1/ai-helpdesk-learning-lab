package lab.helpdesk.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DeploymentSecurityConfigurationTest {

    private static final String[] REQUIRED_PROPERTIES = {
            "helpdesk.deployment.user.username", "helpdesk.deployment.user.password",
            "helpdesk.deployment.agent.username", "helpdesk.deployment.agent.password"
    };

    private ApplicationContextRunner runner(String... profiles) {
        return runnerWithoutEncoder(profiles)
                // Synthetic Unit Test inputs only. Main retains its existing BCrypt configuration.
                .withBean(PasswordEncoder.class, () -> new BCryptPasswordEncoder(4));
    }

    private ApplicationContextRunner runnerWithoutEncoder(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    var environment = context.getEnvironment();
                    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    environment.setActiveProfiles(profiles);
                })
                .withUserConfiguration(DeploymentSecurityConfiguration.class);
    }

    @Test
    void deployment_accounts_use_external_credentials_existing_encoder_and_fixed_roles(CapturedOutput output) {
        String[] values = syntheticValues();
        runner("postgres", "deployment").withPropertyValues(properties(values)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(UserDetailsService.class);
            var users = context.getBean(UserDetailsService.class);
            var encoder = context.getBean(PasswordEncoder.class);
            var user = users.loadUserByUsername(values[0]);
            var agent = users.loadUserByUsername(values[2]);
            assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");
            assertThat(agent.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactlyInAnyOrder("ROLE_USER", "ROLE_AGENT");
            // Assert booleans so a failure does not print a password or hash.
            assertThat(encoder.matches(values[1], user.getPassword())).isTrue();
            assertThat(encoder.matches(values[3], agent.getPassword())).isTrue();
            assertThat(encoder.matches(UUID.randomUUID().toString(), user.getPassword())).isFalse();
            assertThat(output.getAll().contains(user.getPassword())).isFalse();
            assertThat(output.getAll().contains(agent.getPassword())).isFalse();
        });
        assertValuesNotLogged(output, values);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "in-memory"})
    void deployment_profile_is_opt_in(String otherProfile) {
        runner(otherProfile).withPropertyValues(properties(syntheticValues())).run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(UserDetailsService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "helpdesk.deployment.user.username", "helpdesk.deployment.user.password",
            "helpdesk.deployment.agent.username", "helpdesk.deployment.agent.password"
    })
    void each_missing_credential_fails_without_fallback_or_value_logging(String property, CapturedOutput output) {
        String[] values = syntheticValues();
        String[] provided = Arrays.stream(properties(values))
                .filter(value -> !value.startsWith(property + "=")).toArray(String[]::new);
        runner("postgres", "deployment").withPropertyValues(provided).run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage(requiredCode(property)));
        assertValuesNotLogged(output, values);
    }

    static Stream<Arguments> blankCredentials() {
        return Arrays.stream(REQUIRED_PROPERTIES).flatMap(property ->
                Stream.of("", "   ", "\u2003").map(blank -> Arguments.of(property, blank)));
    }

    @ParameterizedTest
    @MethodSource("blankCredentials")
    void each_empty_or_blank_credential_fails_safely(String property, String blank, CapturedOutput output) {
        String[] values = syntheticValues();
        String[] provided = Arrays.stream(properties(values))
                .map(value -> value.startsWith(property + "=") ? property + "=" + blank : value)
                .toArray(String[]::new);
        runner("postgres", "deployment").withPropertyValues(provided).run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage(requiredCode(property)));
        assertValuesNotLogged(output, values);
    }

    @Test
    void local_credentials_are_not_a_deployment_fallback(CapturedOutput output) {
        String[] values = syntheticValues();
        String[] local = Arrays.stream(properties(values))
                .map(value -> value.replace("helpdesk.deployment.", "helpdesk.local."))
                .toArray(String[]::new);
        runner("postgres", "deployment").withPropertyValues(local).run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("HELPDESK_DEPLOYMENT_USER_USERNAME_REQUIRED"));
        assertValuesNotLogged(output, values);
    }

    @ParameterizedTest
    @ValueSource(strings = {"local-browser", "in-memory"})
    void incompatible_profiles_fail_even_with_valid_credentials(String incompatible) {
        String code = "HELPDESK_DEPLOYMENT_" + incompatible.replace('-', '_').toUpperCase(Locale.ROOT)
                + "_PROFILE_CONFLICT";
        runner("postgres", "deployment", incompatible).withPropertyValues(properties(syntheticValues()))
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(code));
    }

    @Test
    void postgres_profile_is_required() {
        runner("deployment").withPropertyValues(properties(syntheticValues())).run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("HELPDESK_DEPLOYMENT_POSTGRES_PROFILE_REQUIRED"));
    }

    @Test
    void case_insensitive_username_collision_is_rejected(CapturedOutput output) {
        String[] values = syntheticValues();
        values[2] = values[0].toUpperCase(Locale.ROOT);
        runner("postgres", "deployment").withPropertyValues(properties(values)).run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("HELPDESK_DEPLOYMENT_USERNAMES_MUST_BE_DISTINCT"));
        assertValuesNotLogged(output, values);
    }

    @Test
    void password_encoder_dependency_is_required() {
        runnerWithoutEncoder("postgres", "deployment").withPropertyValues(properties(syntheticValues()))
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] syntheticValues() {
        return Stream.generate(() -> UUID.randomUUID().toString()).limit(4).toArray(String[]::new);
    }

    private static String[] properties(String[] values) {
        String[] properties = new String[REQUIRED_PROPERTIES.length];
        for (int index = 0; index < REQUIRED_PROPERTIES.length; index++) {
            properties[index] = REQUIRED_PROPERTIES[index] + "=" + values[index];
        }
        return properties;
    }

    private static String requiredCode(String property) {
        return property.replace('.', '_').toUpperCase(Locale.ROOT) + "_REQUIRED";
    }

    private static void assertValuesNotLogged(CapturedOutput output, String[] values) {
        for (String value : values) {
            assertThat(output.getAll().contains(value)).isFalse();
        }
    }
}
