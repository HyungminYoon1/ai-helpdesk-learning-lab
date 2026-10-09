package lab.helpdesk.ai.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.processing.AiSuggestionJobWorker;
import lab.helpdesk.ai.processing.AiSuggestionWorkerConfiguration;
import lab.helpdesk.ai.processing.AiSuggestionWorkerScheduler;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.StandardEnvironment;

import static lab.helpdesk.ai.provider.AiSuggestionProviderConfiguration.KEY_PROPERTY;
import static lab.helpdesk.ai.provider.AiSuggestionProviderConfiguration.SUMMARY_LIMIT_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Main Configuration with synthetic config-tree files, no Database, Worker Tick, or AI call. */
class AiSuggestionProviderConfigurationTest {

    @TempDir
    Path secretDirectory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                // Do not read the caller's real Key or other global runtime configuration.
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                new ConfigDataApplicationContextInitializer().initialize(context);
            })
            .withPropertyValues("spring.config.name=week8-provider-configuration-test",
                    "spring.profiles.active=postgres", "helpdesk.ai.worker.enabled=false")
            .withUserConfiguration(AiSuggestionProviderConfiguration.class);

    @Test
    void the_default_does_not_require_secrets_or_register_a_live_provider() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(AiSuggestionProvider.class)
                .doesNotHaveBean(AiSuggestionOutputValidator.class));
    }

    @Test
    void explicit_false_does_not_require_secrets_or_register_a_live_provider() {
        runner.withPropertyValues("helpdesk.ai.provider.enabled=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(AiSuggestionProvider.class)
                        .doesNotHaveBean(AiSuggestionOutputValidator.class));
    }

    @Test
    void an_optional_missing_config_tree_is_allowed_when_the_provider_is_disabled() {
        runner.withPropertyValues("spring.config.import=optional:configtree:" + missingTreeLocation())
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AiSuggestionProvider.class));
    }

    @Test
    void optional_config_tree_does_not_make_the_enabled_providers_key_optional() {
        runner.withPropertyValues("spring.config.import=optional:configtree:" + missingTreeLocation(),
                        "helpdesk.ai.provider.enabled=true", SUMMARY_LIMIT_PROPERTY + "=8")
                .withUserConfiguration(PrivacyFixtureConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("HELPDESK_AI_PROVIDER_KEY_REQUIRED");
                });
    }

    @Test
    void the_in_memory_profile_does_not_register_the_postgres_provider_configuration() {
        runner.withPropertyValues("spring.profiles.active=in-memory", "helpdesk.ai.provider.enabled=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(AiSuggestionProvider.class));
    }

    @Test
    void the_main_configuration_reads_a_file_and_constructs_the_provider_without_calling_it() throws IOException {
        writeFixture(KEY_PROPERTY, UUID.randomUUID().toString());
        writeFixture(SUMMARY_LIMIT_PROPERTY, "8");

        enabledRunner().run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(AiSuggestionProvider.class)
                    .hasSingleBean(AiSuggestionOutputValidator.class);
            var provider = context.getBean(SpringAiOpenAiSuggestionProvider.class);
            assertThat(context.getBean(AiSuggestionProvider.class)).isSameAs(provider);
            assertThat(provider.lastCallEvidence().httpAttempts()).isZero();
        });
    }

    @Test
    void a_missing_key_is_rejected_at_startup() throws IOException {
        writeFixture(SUMMARY_LIMIT_PROPERTY, "8");

        enabledRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("HELPDESK_AI_PROVIDER_KEY_REQUIRED");
        });
    }

    @Test
    void a_blank_key_is_rejected_at_startup() throws IOException {
        writeFixture(KEY_PROPERTY, " \n\t");
        writeFixture(SUMMARY_LIMIT_PROPERTY, "8");

        enabledRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("HELPDESK_AI_PROVIDER_KEY_REQUIRED");
        });
    }

    @Test
    void a_global_openai_key_is_not_used_as_a_missing_helpdesk_key_fallback() throws IOException {
        writeFixture(SUMMARY_LIMIT_PROPERTY, "8");

        enabledRunner().withPropertyValues("OPENAI_API_KEY=" + UUID.randomUUID())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("HELPDESK_AI_PROVIDER_KEY_REQUIRED");
                });
    }

    @Test
    void a_summary_limit_is_required_instead_of_silently_defaulting_to_a_test_value() throws IOException {
        writeFixture(KEY_PROPERTY, UUID.randomUUID().toString());

        enabledRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("HELPDESK_AI_SUMMARY_LIMIT_REQUIRED");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number", "2147483648"})
    void invalid_summary_limits_are_rejected(String value) throws IOException {
        writeFixture(KEY_PROPERTY, UUID.randomUUID().toString());
        writeFixture(SUMMARY_LIMIT_PROPERTY, value);

        enabledRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("HELPDESK_AI_SUMMARY_LIMIT_INVALID");
        });
    }

    @Test
    void an_invalid_summary_value_is_not_copied_into_the_failure_message_or_cause() throws IOException {
        String syntheticSensitiveValue = UUID.randomUUID().toString();
        writeFixture(KEY_PROPERTY, UUID.randomUUID().toString());
        writeFixture(SUMMARY_LIMIT_PROPERTY, syntheticSensitiveValue);

        enabledRunner().run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root.getMessage()).isEqualTo("HELPDESK_AI_SUMMARY_LIMIT_INVALID");
            assertThat(root.getMessage().contains(syntheticSensitiveValue)).isFalse();
            assertThat(root.getCause()).isNull();
        });
    }

    @Test
    void the_privacy_guard_must_be_supplied_explicitly() throws IOException {
        writeFixture(KEY_PROPERTY, UUID.randomUUID().toString());
        writeFixture(SUMMARY_LIMIT_PROPERTY, "8");

        treeRunner().withPropertyValues("helpdesk.ai.provider.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                            .hasStackTraceContaining(AiInputPrivacyGuard.class.getName());
                });
    }

    @Test
    void the_output_validator_uses_the_explicit_summary_limit() throws IOException {
        writeFixture(KEY_PROPERTY, UUID.randomUUID().toString());
        writeFixture(SUMMARY_LIMIT_PROPERTY, "8");

        enabledRunner().run(context -> {
            assertThat(context).hasNotFailed();
            var validator = context.getBean(AiSuggestionOutputValidator.class);
            assertThat(validator.validate(outputWithSummary("가".repeat(8))).summary())
                    .isEqualTo("가".repeat(8));
            assertThatThrownBy(() -> validator.validate(outputWithSummary("가".repeat(9))))
                    .isInstanceOf(AiSuggestionOutputValidator.InvalidOutputException.class)
                    .hasMessage("AI_OUTPUT_SUMMARY");
            assertThat(context.getBean(SpringAiOpenAiSuggestionProvider.class).lastCallEvidence().httpAttempts())
                    .isZero();
        });
    }

    @Test
    void registering_the_provider_does_not_enable_the_worker() throws IOException {
        writeFixture(KEY_PROPERTY, UUID.randomUUID().toString());
        writeFixture(SUMMARY_LIMIT_PROPERTY, "8");

        enabledRunner().withUserConfiguration(AiSuggestionWorkerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(AiSuggestionProvider.class)
                            .doesNotHaveBean(AiSuggestionJobWorker.class)
                            .doesNotHaveBean(AiSuggestionWorkerScheduler.class);
                    assertThat(context.getBean(SpringAiOpenAiSuggestionProvider.class).lastCallEvidence().httpAttempts())
                            .isZero();
                });
    }

    private ApplicationContextRunner enabledRunner() {
        return treeRunner().withPropertyValues("helpdesk.ai.provider.enabled=true")
                .withUserConfiguration(PrivacyFixtureConfiguration.class);
    }

    private ApplicationContextRunner treeRunner() {
        String location = secretDirectory.toAbsolutePath().toString().replace('\\', '/') + "/";
        return runner.withPropertyValues("spring.config.import=configtree:" + location);
    }

    private String missingTreeLocation() {
        return secretDirectory.resolve("missing").toAbsolutePath().toString().replace('\\', '/') + "/";
    }

    private void writeFixture(String name, String value) throws IOException {
        Files.writeString(secretDirectory.resolve(name), value, StandardCharsets.UTF_8);
    }

    private String outputWithSummary(String summary) {
        return "{\"decision\":\"SUGGEST\",\"summary\":\"" + summary
                + "\",\"categories\":[\"ACCOUNT\"],\"priority\":\"NORMAL\"}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PrivacyFixtureConfiguration {

        @Bean
        AiInputPrivacyGuard privacy() {
            return new AiInputPrivacyGuard(List.of(new AiInputPrivacyGuard.SensitiveFragment(
                    "SYNTHETIC_EMAIL_VALUE", AiInputPrivacyGuard.SensitiveType.EMAIL)));
        }
    }
}
