package lab.helpdesk.ai.processing;

import java.util.List;
import java.util.Map;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiSuggestionInputRepository;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiSuggestionWorkerConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiSuggestionWorkerConfiguration.class);

    @Test
    void automatic_processing_is_disabled_when_the_opt_in_property_is_absent() {
        runner.withPropertyValues("spring.profiles.active=postgres").run(context ->
                assertThat(context).hasNotFailed()
                        .doesNotHaveBean(AiSuggestionJobWorker.class)
                        .doesNotHaveBean(AiSuggestionWorkerScheduler.class));
    }

    @Test
    void false_opt_in_keeps_automatic_processing_disabled() {
        runner.withPropertyValues("spring.profiles.active=postgres", "helpdesk.ai.worker.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AiSuggestionJobWorker.class));
    }

    @Test
    void the_worker_is_not_registered_in_the_in_memory_profile() {
        runner.withPropertyValues("spring.profiles.active=in-memory", "helpdesk.ai.worker.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AiSuggestionJobWorker.class));
    }

    @Test
    void enabled_processing_does_not_silently_create_live_dependencies() {
        runner.withPropertyValues("spring.profiles.active=postgres", "helpdesk.ai.worker.enabled=true")
                .withBean(AiSuggestionJobClaimService.class, () -> mock(AiSuggestionJobClaimService.class))
                .withBean(AiSuggestionInputRepository.class, () -> mock(AiSuggestionInputRepository.class))
                .withBean(AiSuggestionResultService.class, () -> mock(AiSuggestionResultService.class))
                .withBean(AiInputPrivacyGuard.class, () -> new AiInputPrivacyGuard(List.of(
                        new AiInputPrivacyGuard.SensitiveFragment("SYNTHETIC_CONTACT", AiInputPrivacyGuard.SensitiveType.EMAIL))))
                .withBean(AiSuggestionOutputValidator.class, () -> new AiSuggestionOutputValidator(200))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                            .hasStackTraceContaining(AiSuggestionProvider.class.getName());
                });
    }

    @Test
    void invalid_poll_intervals_are_rejected_and_the_default_is_one_second() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of()));
        assertThat(binder.bindOrCreate("helpdesk.ai.worker", AiSuggestionWorkerSettings.class).pollDelayMs())
                .isEqualTo(1000);
        assertThatThrownBy(() -> new AiSuggestionWorkerSettings(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_WORKER_POLL_DELAY_INVALID");
        assertThatThrownBy(() -> new AiSuggestionWorkerSettings(-1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_WORKER_POLL_DELAY_INVALID");
    }

    @Test
    void storage_defaults_and_overrides_are_bound_to_the_worker_settings() {
        var defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("helpdesk.ai.worker", AiSuggestionWorkerSettings.class);
        assertThat(defaults.maxStorageAttempts()).isEqualTo(3);
        assertThat(defaults.storageRetryDelayMs()).isEqualTo(5000);

        var configured = new Binder(new MapConfigurationPropertySource(Map.of(
                "helpdesk.ai.worker.poll-delay-ms", "250",
                "helpdesk.ai.worker.max-storage-attempts", "2",
                "helpdesk.ai.worker.storage-retry-delay-ms", "7000")))
                .bindOrCreate("helpdesk.ai.worker", AiSuggestionWorkerSettings.class);
        assertThat(configured).isEqualTo(new AiSuggestionWorkerSettings(250, 2, 7000));
    }

    @Test
    void invalid_storage_limits_are_rejected() {
        assertThatThrownBy(() -> new AiSuggestionWorkerSettings(1000, 0, 5000))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_WORKER_STORAGE_RETRY_INVALID");
        assertThatThrownBy(() -> new AiSuggestionWorkerSettings(1000, 3, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_WORKER_STORAGE_RETRY_INVALID");
        assertThatThrownBy(() -> new AiSuggestionWorkerSettings(1000, 3, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_WORKER_STORAGE_RETRY_INVALID");
    }
}
