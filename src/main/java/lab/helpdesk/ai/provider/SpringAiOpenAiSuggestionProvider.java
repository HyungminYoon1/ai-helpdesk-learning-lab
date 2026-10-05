package lab.helpdesk.ai.provider;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.LogLevel;
import io.micrometer.observation.ObservationRegistry;
import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.PreparedInput;
import lab.helpdesk.ai.job.AiJobRequestKind;
import lab.helpdesk.ai.provider.AiProviderFailureException.Kind;
import lab.helpdesk.ai.provider.AiProviderFailureException.Reason;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly constructed, synchronous, single-call Adapter; it is not an auto-started Bean. */
public final class SpringAiOpenAiSuggestionProvider implements AiSuggestionProvider, AutoCloseable {

    public static final String MODEL = "gpt-6-luna";
    public static final String PROMPT_VERSION = "prompt-v4-policy-alignment-chat-v1";
    public static final int MAX_OUTPUT_TOKENS = 600;
    private static final String OFFICIAL_BASE_URL = "https://api.openai.com/v1";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final SingleAttemptOpenAiHttpClient transport;
    private final OpenAIClient client;
    private final OpenAiChatModel model;
    private final OpenAiChatOptions chatOptions;
    private final String instructions;

    /** Key must be passed explicitly from a Helpdesk-scoped source; no fromEnv() fallback. */
    public SpringAiOpenAiSuggestionProvider(String helpdeskApiKey, AiInputPrivacyGuard privacy,
            int maxSummaryCodePoints) {
        this(helpdeskApiKey, privacy, maxSummaryCodePoints, OFFICIAL_BASE_URL, Clock.systemUTC());
    }

    /** Test seam: only the official endpoint or a literal loopback fixture may receive the key. */
    SpringAiOpenAiSuggestionProvider(String helpdeskApiKey, AiInputPrivacyGuard privacy,
            int maxSummaryCodePoints, String baseUrl, Clock clock) {
        if (helpdeskApiKey == null || helpdeskApiKey.isBlank() || maxSummaryCodePoints < 1) {
            throw new AiProviderFailureException(Kind.CONFIGURATION, Reason.REQUEST_REJECTED, null);
        }
        requireSafeEndpoint(baseUrl);
        Objects.requireNonNull(privacy);
        Objects.requireNonNull(clock);
        AiInputPrivacyGuard credentialGuard = new AiInputPrivacyGuard(List.of(
                new AiInputPrivacyGuard.SensitiveFragment(helpdeskApiKey, AiInputPrivacyGuard.SensitiveType.PASSWORD)));
        transport = new SingleAttemptOpenAiHttpClient(baseUrl, privacy, credentialGuard, clock);
        ClientOptions options = ClientOptions.builder().httpClient(transport)
                .baseUrl(baseUrl).apiKey(helpdeskApiKey).maxRetries(0).logLevel(LogLevel.OFF)
                .timeout(Duration.ofSeconds(60)).build();
        client = new OpenAIClientImpl(options);
        chatOptions = OpenAiChatOptions.builder().model(MODEL).reasoningEffort("none")
                .serviceTier("default").store(false).n(1).maxCompletionTokens(MAX_OUTPUT_TOKENS)
                .maxRetries(0).responseFormat(OpenAiChatModel.ResponseFormat.builder()
                        .jsonSchema(OpenAiSuggestionContract.schema()).strict(true).build())
                .build();
        model = OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async())
                .observationRegistry(ObservationRegistry.NOOP)
                .options(chatOptions)
                .build();
        instructions = OpenAiSuggestionContract.instructions(maxSummaryCodePoints);
    }

    @Override
    public synchronized String generate(PreparedInput input, Duration timeout, AiJobRequestKind requestKind) {
        transport.resetEvidence();
        if (input == null || input.title() == null || input.title().isBlank()
                || input.body() == null || input.body().isBlank() || timeout == null
                || timeout.isNegative() || timeout.toMillis() < 1 || requestKind == null) {
            throw new AiProviderFailureException(Kind.CONFIGURATION, Reason.REQUEST_REJECTED, null);
        }
        String policy = instructions;
        if (requestKind == AiJobRequestKind.OUTPUT_REPAIR) {
            policy += "\n앞선 응답의 필수 필드가 누락됐다. 네 필드를 모두 명시하고 원문에서 다시 판단한다.";
        }
        var content = MAPPER.createObjectNode().put("title", input.title()).put("body", input.body());
        try {
            // A new timeout-only Builder has its own default Model. Copy all fixed options
            // rather than letting those defaults override the approved Model during merging.
            OpenAiChatOptions requestOptions = chatOptions.mutate().timeout(timeout).build();
            var response = model.call(new Prompt(List.of(new SystemMessage(policy),
                    new UserMessage(MAPPER.writeValueAsString(content))),
                    requestOptions));
            if (response.getResults().size() != 1 || response.getResult().getOutput().getText() == null) {
                throw new AiProviderFailureException(Kind.INVALID_RESPONSE, Reason.MALFORMED_ENVELOPE, null);
            }
            return response.getResult().getOutput().getText();
        } catch (AiProviderFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // SDK errors can contain raw headers/body. Retain none of them or their causes.
            throw new AiProviderFailureException(Kind.OUTCOME_UNKNOWN, Reason.MALFORMED_ENVELOPE, null);
        }
    }

    public synchronized AiProviderCallEvidence lastCallEvidence() {
        return transport.evidence();
    }

    private static void requireSafeEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            boolean official = OFFICIAL_BASE_URL.equals(value);
            boolean fixture = "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() > 0 && "/v1".equals(uri.getPath());
            if ((!official && !fixture) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new AiProviderFailureException(Kind.CONFIGURATION, Reason.REQUEST_REJECTED, null);
        }
    }

    @Override
    public synchronized void close() {
        client.close();
    }

    @Override
    public String toString() {
        return "SpringAiOpenAiSuggestionProvider[model=" + MODEL + ", credentials and content omitted]";
    }
}
