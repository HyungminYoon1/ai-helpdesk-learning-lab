package lab.helpdesk.ai.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import lab.helpdesk.ai.job.AiJobPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Controlled environment names only: no host credentials, Database, Scheduler or HTTP. */
class SyntheticCloudSettingsBindingTest {

    private final MockEnvironment environment = environmentFromTemplate();

    @Test
    void cloud_environment_names_bind_one_generation_and_no_output_repair() {
        AiJobPolicy policy = Binder.get(environment).bind("helpdesk.ai.job", AiJobPolicy.class)
                .orElseThrow(() -> new IllegalStateException("SYNTHETIC_POLICY_NOT_BOUND"));

        assertThat(policy.policyVersion()).isEqualTo("cloud-synthetic-once-v1");
        assertThat(policy.maxGenerationAttempts()).isOne();
        assertThat(policy.maxOutputRepairAttempts()).isZero();
        assertThat(policy.requestTimeoutMs()).isEqualTo(60_000);
        assertThat(policy.attemptLeaseMs()).isEqualTo(120_000);
        assertThat(policy.retryBackoffMs()).isEqualTo(5_000);
        assertThat(policy.jobProcessingTimeoutMs()).isEqualTo(300_000);
    }

    @Test
    void environment_names_resolve_the_existing_provider_and_scanner_properties() {
        assertThat(environment.getActiveProfiles()).containsExactly("postgres", "deployment", "synthetic-ai");
        assertThat(environment.getProperty("helpdesk.ai.provider.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("helpdesk.ai.worker.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("helpdesk.ai.provider.max-summary-code-points", Integer.class))
                .isEqualTo(200);
        assertThat(environment.getProperty("helpdesk.ai.synthetic-scan.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("helpdesk.ai.synthetic-scan.scanner-jar"))
                .isEqualTo("/app/helpdesk.jar");
        assertThat(environment.getProperty("helpdesk.ai.synthetic-scan.timeout-ms", Integer.class))
                .isEqualTo(20_000);
        assertThat(environment.getProperty("helpdesk.ai.synthetic-scan.cleanup-timeout-ms", Integer.class))
                .isEqualTo(3_000);
    }

    @Test
    void public_environment_contains_neither_a_key_nor_a_global_fallback() {
        assertThat(environment.containsProperty("helpdesk.ai.provider.key")).isFalse();
        assertThat(environment.containsProperty("OPENAI_API_KEY")).isFalse();
    }

    @Test
    void misspelled_limit_names_leave_the_defaults_unchanged() {
        var wrongNames = new MockEnvironment();
        wrongNames.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "negative-fixture-systemEnvironment", Map.of(
                        "HELPDESK_AI_JOB_POLICYVERSION", "negative-fixture-v1",
                        "HELPDESK_AI_JOB_MAXGENERATIONATTEMPT", "1",
                        "HELPDESK_AI_JOB_MAXOUTPUTREPAIRATTEMPT", "0")));

        AiJobPolicy policy = Binder.get(wrongNames).bind("helpdesk.ai.job", AiJobPolicy.class)
                .orElseThrow(() -> new IllegalStateException("SYNTHETIC_POLICY_NOT_BOUND"));
        assertThat(policy.maxGenerationAttempts()).isEqualTo(3);
        assertThat(policy.maxOutputRepairAttempts()).isOne();
    }

    private static MockEnvironment environmentFromTemplate() {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            var root = JsonMapper.builder().build().readTree(Files.readString(
                    Path.of("deploy/ecs/synthetic-ai-container-settings.template.json"), StandardCharsets.UTF_8));
            for (var entry : root.get("environment")) {
                String name = entry.get("name").stringValue();
                if (values.put(name, entry.get("value").stringValue()) != null) {
                    throw new IllegalStateException("SYNTHETIC_SETTINGS_DUPLICATE_NAME");
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("SYNTHETIC_SETTINGS_TEMPLATE_INVALID");
        }
        var environment = new MockEnvironment();
        // Boot's environment-variable mapper recognizes this suffix, just as in a real OS environment.
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "controlled-synthetic-systemEnvironment", values));
        return environment;
    }
}
