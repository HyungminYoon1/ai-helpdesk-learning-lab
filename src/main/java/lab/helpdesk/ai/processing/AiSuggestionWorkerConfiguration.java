package lab.helpdesk.ai.processing;

import java.time.Clock;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiSuggestionInputRepository;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Opt-in only. A Provider, privacy guard, and output limit must be supplied explicitly. */
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
@ConditionalOnProperty(prefix = "helpdesk.ai.worker", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AiSuggestionWorkerSettings.class)
@EnableScheduling
public class AiSuggestionWorkerConfiguration {

    @Bean
    AiSuggestionJobProcessor aiSuggestionJobProcessor(AiSuggestionJobClaimService claims,
            AiSuggestionInputRepository inputs, AiInputPrivacyGuard privacy,
            AiSuggestionProvider provider, AiSuggestionOutputValidator validator,
            AiSuggestionResultService results) {
        return new AiSuggestionJobProcessor(claims, inputs, privacy, provider, validator, results, Clock.systemUTC());
    }

    @Bean
    AiSuggestionJobWorker aiSuggestionJobWorker(AiSuggestionJobProcessor processor,
            AiSuggestionJobClaimService claims, AiSuggestionWorkerSettings settings) {
        return new AiSuggestionJobWorker(processor, claims, settings, Clock.systemUTC());
    }

    @Bean
    AiSuggestionWorkerScheduler aiSuggestionWorkerScheduler(AiSuggestionJobWorker worker,
            AiSuggestionWorkerSettings settings) {
        // Binding validates the same interval used by @Scheduled before starting the poller.
        return new AiSuggestionWorkerScheduler(worker);
    }
}
