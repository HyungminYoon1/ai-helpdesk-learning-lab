package lab.helpdesk.ai.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiJobPolicyTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("postgres"))
            .withUserConfiguration(AiJobConfiguration.class);

    @Test
    void approved_defaults_are_bound_without_credentials() {
        contextRunner.run(context -> assertThat(context.getBean(AiJobPolicy.class))
                .isEqualTo(new AiJobPolicy("job-policy-v1", 3, 1, 60000, 120000, 5000, 300000)));
    }

    @Test
    void configuration_can_change_the_policy_for_new_jobs() {
        contextRunner.withPropertyValues(
                "helpdesk.ai.job.policy-version=job-policy-v2",
                "helpdesk.ai.job.max-generation-attempts=5",
                "helpdesk.ai.job.max-output-repair-attempts=2",
                "helpdesk.ai.job.request-timeout-ms=10000",
                "helpdesk.ai.job.attempt-lease-ms=20000",
                "helpdesk.ai.job.retry-backoff-ms=1000",
                "helpdesk.ai.job.job-processing-timeout-ms=90000")
                .run(context -> assertThat(context.getBean(AiJobPolicy.class))
                        .isEqualTo(new AiJobPolicy("job-policy-v2", 5, 2, 10000, 20000, 1000, 90000)));
    }

    @Test
    void in_memory_profile_does_not_enable_the_job_policy() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("in-memory"))
                .withUserConfiguration(AiJobConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(AiJobPolicy.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "version/invalid"})
    void invalid_policy_version_is_rejected_without_echoing_input(String version) {
        assertThatThrownBy(() -> new AiJobPolicy(version, 3, 1, 60000, 120000, 5000, 300000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid job policy version");
    }

    @Test
    void zero_generation_cap_is_rejected() {
        assertThatThrownBy(() -> new AiJobPolicy("test-policy", 0, 1, 60000, 120000, 5000, 300000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negative_repair_cap_is_rejected() {
        assertThatThrownBy(() -> new AiJobPolicy("test-policy", 3, -1, 60000, 120000, 5000, 300000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lease_must_outlast_request_timeout() {
        assertThatThrownBy(() -> new AiJobPolicy("test-policy", 3, 1, 60000, 60000, 5000, 300000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid job time limits");
    }

    @Test
    void repair_cap_can_exceed_generation_cap_but_both_must_be_checked_at_runtime() {
        assertThat(new AiJobPolicy("test-policy", 2, 2, 60000, 120000, 5000, 300000)
                .maxGenerationAttempts()).isEqualTo(2);
    }
}
