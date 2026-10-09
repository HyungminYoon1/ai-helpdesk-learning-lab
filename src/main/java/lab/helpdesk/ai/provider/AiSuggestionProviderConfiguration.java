package lab.helpdesk.ai.provider;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Explicit Provider assembly only; registering it does not enable the Worker. */
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
@ConditionalOnProperty(prefix = "helpdesk.ai.provider", name = "enabled", havingValue = "true")
public class AiSuggestionProviderConfiguration {

    static final String KEY_PROPERTY = "helpdesk.ai.provider.key";
    static final String SUMMARY_LIMIT_PROPERTY = "helpdesk.ai.provider.max-summary-code-points";

    @Bean
    ProviderLimits aiProviderLimits(Environment environment) {
        String value = environment.getProperty(SUMMARY_LIMIT_PROPERTY);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("HELPDESK_AI_SUMMARY_LIMIT_REQUIRED");
        }
        int limit;
        try {
            limit = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            // A misplaced secret could be the invalid value. Do not retain the parser's cause.
            throw new IllegalStateException("HELPDESK_AI_SUMMARY_LIMIT_INVALID");
        }
        if (limit < 1) {
            throw new IllegalStateException("HELPDESK_AI_SUMMARY_LIMIT_INVALID");
        }
        return new ProviderLimits(limit);
    }

    @Bean
    AiSuggestionOutputValidator aiSuggestionOutputValidator(ProviderLimits limits) {
        return new AiSuggestionOutputValidator(limits.maxSummaryCodePoints());
    }

    @Bean(destroyMethod = "close")
    SpringAiOpenAiSuggestionProvider aiSuggestionProvider(Environment environment,
            AiInputPrivacyGuard privacy, ProviderLimits limits) {
        String key = environment.getProperty(KEY_PROPERTY);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("HELPDESK_AI_PROVIDER_KEY_REQUIRED");
        }
        // No OPENAI_API_KEY fallback, empty privacy guard, or request during Bean creation.
        return new SpringAiOpenAiSuggestionProvider(key, privacy, limits.maxSummaryCodePoints());
    }

    record ProviderLimits(int maxSummaryCodePoints) {
    }
}
