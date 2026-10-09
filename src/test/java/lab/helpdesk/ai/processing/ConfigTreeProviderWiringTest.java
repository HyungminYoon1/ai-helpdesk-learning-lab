package lab.helpdesk.ai.processing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.PreparedInput;
import lab.helpdesk.ai.job.AiJobRequestKind;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/** Learning fixture only: no real credentials, HTTP client, database, or scheduled work. */
class ConfigTreeProviderWiringTest {

    private static final String KEY_PROPERTY = "lesson.provider.key";

    @TempDir
    Path secretDirectory;

    private final AtomicBoolean keyReadFromConfigData = new AtomicBoolean();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                new ConfigDataApplicationContextInitializer().initialize(context);
                keyReadFromConfigData.set(context.getEnvironment().containsProperty(KEY_PROPERTY));
            })
            .withPropertyValues("spring.config.name=week8-configtree-lesson",
                    "spring.profiles.active=postgres");

    @Test
    void a_file_value_is_read_and_passed_to_a_registered_provider_without_calling_it() throws IOException {
        String syntheticValue = UUID.randomUUID().toString();
        writeFixture(syntheticValue);

        treeRunner().withPropertyValues("lesson.provider.enabled=true")
                .withUserConfiguration(LessonProviderConfiguration.class, LessonConsumerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AiSuggestionProvider.class);
                    NonCallingProvider provider = (NonCallingProvider) context.getBean(AiSuggestionProvider.class);
                    // Compare booleans, not secret strings that an assertion could print on failure.
                    assertThat(provider.received(syntheticValue)).isTrue();
                    assertThat(context.getBean(ProviderConsumer.class).provider()).isSameAs(provider);
                    assertThat(provider.invocations).isZero();
                });
    }

    @Test
    void reading_the_file_does_not_register_a_provider_bean() throws IOException {
        writeFixture(UUID.randomUUID().toString());

        treeRunner().withPropertyValues("lesson.provider.enabled=true")
                .withUserConfiguration(LessonConsumerConfiguration.class)
                .run(context -> {
                    // Observe configuration before refresh; a failed runner Context cannot be queried normally.
                    assertThat(keyReadFromConfigData.get()).isTrue();
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
                });
    }

    @Test
    void an_enabled_provider_with_a_missing_required_value_fails_at_startup() {
        treeRunner().withPropertyValues("lesson.provider.enabled=true")
                .withUserConfiguration(LessonProviderConfiguration.class, LessonConsumerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void a_blank_value_is_not_a_usable_required_value() throws IOException {
        writeFixture(" \n\t");

        treeRunner().withPropertyValues("lesson.provider.enabled=true")
                .withUserConfiguration(LessonProviderConfiguration.class, LessonConsumerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("LESSON_PROVIDER_KEY_REQUIRED");
                });
    }

    @Test
    void a_disabled_feature_does_not_require_its_key_or_start_the_real_worker() {
        treeRunner().withPropertyValues("lesson.provider.enabled=false", "helpdesk.ai.worker.enabled=false")
                .withUserConfiguration(LessonProviderConfiguration.class, LessonConsumerConfiguration.class,
                        AiSuggestionWorkerConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(AiSuggestionProvider.class)
                        .doesNotHaveBean(ProviderConsumer.class)
                        .doesNotHaveBean(AiSuggestionJobWorker.class)
                        .doesNotHaveBean(AiSuggestionWorkerScheduler.class));
    }

    private ApplicationContextRunner treeRunner() {
        String location = secretDirectory.toAbsolutePath().toString().replace('\\', '/') + "/";
        return runner.withPropertyValues("spring.config.import=configtree:" + location);
    }

    private void writeFixture(String value) throws IOException {
        Files.writeString(secretDirectory.resolve(KEY_PROPERTY), value, StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "lesson.provider", name = "enabled", havingValue = "true")
    static class LessonProviderConfiguration {

        @Bean
        AiSuggestionProvider lessonProvider(Environment environment) {
            String key = environment.getRequiredProperty(KEY_PROPERTY);
            if (key.isBlank()) {
                throw new IllegalStateException("LESSON_PROVIDER_KEY_REQUIRED");
            }
            return new NonCallingProvider(key);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "lesson.provider", name = "enabled", havingValue = "true")
    static class LessonConsumerConfiguration {

        @Bean
        ProviderConsumer lessonConsumer(AiSuggestionProvider provider) {
            return new ProviderConsumer(provider);
        }
    }

    record ProviderConsumer(AiSuggestionProvider provider) {
    }

    static final class NonCallingProvider implements AiSuggestionProvider {
        private final String configuredKey;
        private int invocations;

        NonCallingProvider(String configuredKey) {
            this.configuredKey = configuredKey;
        }

        boolean received(String expected) {
            return configuredKey.equals(expected);
        }

        @Override
        public String generate(PreparedInput input, Duration timeout, AiJobRequestKind requestKind) {
            invocations++;
            throw new AssertionError("PROVIDER_CALL_NOT_ALLOWED_IN_WIRING_LESSON");
        }

        @Override
        public String toString() {
            return "NonCallingProvider[configured value omitted]";
        }
    }
}
