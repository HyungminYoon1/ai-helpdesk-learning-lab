package lab.helpdesk.ai.provider;

import java.time.Clock;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import org.springframework.core.env.Environment;

/** Test-only access to the existing literal-loopback constructor; never in the App JAR. */
public final class SyntheticRuntimeProviderBridge {
    private SyntheticRuntimeProviderBridge() { }

    public static SpringAiOpenAiSuggestionProvider create(Environment environment, AiInputPrivacyGuard guard, int port) {
        var configuration = new AiSuggestionProviderConfiguration();
        var limits = configuration.aiProviderLimits(environment);
        String key = environment.getProperty(AiSuggestionProviderConfiguration.KEY_PROPERTY);
        if (key == null || key.isBlank()) throw new IllegalStateException("HELPDESK_AI_PROVIDER_KEY_REQUIRED");
        return new SpringAiOpenAiSuggestionProvider(key, guard, limits.maxSummaryCodePoints(),
                "http://127.0.0.1:" + port + "/v1", Clock.systemUTC());
    }

    public static AiSuggestionOutputValidator validator(Environment environment) {
        var configuration = new AiSuggestionProviderConfiguration();
        return configuration.aiSuggestionOutputValidator(configuration.aiProviderLimits(environment));
    }
}
