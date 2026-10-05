package lab.helpdesk.ai.provider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.provider.AiProviderCallEvidence.TokenUsage;
import lab.helpdesk.ai.provider.AiProviderFailureException.Kind;
import lab.helpdesk.ai.provider.AiProviderFailureException.Reason;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SDK transport for this synchronous Adapter only. It checks the exact outbound body,
 * has no retries/redirects/proxy, and rejects unsafe envelopes before Spring AI can log them.
 */
final class SingleAttemptOpenAiHttpClient implements HttpClient {

    static final int MAX_REQUEST_BYTES = 32_768;
    static final int MAX_RESPONSE_BYTES = 65_536;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .proxy(Proxy.NO_PROXY).build();
    private final AiInputPrivacyGuard privacy;
    private final AiInputPrivacyGuard credentialGuard;
    private final String endpoint;
    private final Clock clock;
    private AiProviderCallEvidence evidence = new AiProviderCallEvidence(0, null, null);

    SingleAttemptOpenAiHttpClient(String baseUrl, AiInputPrivacyGuard privacy,
            AiInputPrivacyGuard credentialGuard, Clock clock) {
        this.endpoint = baseUrl + "/chat/completions";
        this.privacy = Objects.requireNonNull(privacy);
        this.credentialGuard = Objects.requireNonNull(credentialGuard);
        this.clock = Objects.requireNonNull(clock);
    }

    void resetEvidence() {
        evidence = new AiProviderCallEvidence(0, null, null);
    }

    AiProviderCallEvidence evidence() {
        return evidence;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        if (evidence.httpAttempts() != 0 || !"POST".equals(request.method().name()) || !endpoint.equals(request.url())
                || request.body() == null || options.getTimeout() == null) {
            throw failure(Kind.CONFIGURATION, Reason.REQUEST_REJECTED);
        }
        Duration timeout = options.getTimeout().request();
        if (timeout == null || timeout.toMillis() <= 0) {
            throw failure(Kind.CONFIGURATION, Reason.REQUEST_REJECTED);
        }
        byte[] checked = checkedBody(request);
        Request.Builder outgoing = new Request.Builder().url(endpoint);
        for (String name : request.headers().names()) {
            for (String value : request.headers().values(name)) {
                outgoing.addHeader(name, value);
            }
        }
        outgoing.post(RequestBody.create(checked, MediaType.get("application/json; charset=utf-8")));
        var call = client.newBuilder().connectTimeout(timeout).readTimeout(timeout).writeTimeout(timeout)
                .callTimeout(timeout).build().newCall(outgoing.build());
        evidence = new AiProviderCallEvidence(1, null, null);
        try (Response response = call.execute()) {
            evidence = new AiProviderCallEvidence(1, response.code(), null);
            byte[] bytes = response.body() == null ? new byte[0]
                    : response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw failure(Kind.INVALID_RESPONSE, Reason.RESPONSE_TOO_LARGE);
            }
            JsonNode envelope = parseEnvelope(bytes);
            evidence = new AiProviderCallEvidence(1, response.code(), usage(envelope),
                    expectedTariff(envelope));
            requireUsableResponse(response.code(), response.header("Retry-After"), envelope);
            Headers.Builder headers = Headers.builder();
            for (String name : response.headers().names()) {
                headers.put(name, response.headers(name));
            }
            return new BufferedResponse(response.code(), headers.build(), bytes);
        } catch (IOException exception) {
            // Neither connection/timeout exceptions nor HTTP payloads are retained.
            throw failure(Kind.OUTCOME_UNKNOWN, Reason.TRANSPORT);
        }
    }

    private byte[] checkedBody(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
            @Override
            public synchronized void write(byte[] value, int offset, int length) {
                if (length > MAX_REQUEST_BYTES - count) {
                    throw failure(Kind.CONFIGURATION, Reason.REQUEST_REJECTED);
                }
                super.write(value, offset, length);
            }

            @Override
            public synchronized void write(int value) {
                if (count == MAX_REQUEST_BYTES) {
                    throw failure(Kind.CONFIGURATION, Reason.REQUEST_REJECTED);
                }
                super.write(value);
            }
        };
        try {
            request.body().writeTo(bytes);
            String serialized = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            privacy.checkSerializedRequest(serialized);
            credentialGuard.checkSerializedRequest(serialized);
        } catch (AiProviderFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(Kind.CONFIGURATION, Reason.REQUEST_REJECTED);
        }
        return bytes.toByteArray();
    }

    private void requireUsableResponse(int status, String retryHeader, JsonNode root) {
        if (status == 401 || status == 403) {
            throw failure(Kind.CONFIGURATION, Reason.AUTHENTICATION);
        }
        String code = text(root == null ? null : root.path("error").path("code"));
        if (status == 429 && ("insufficient_quota".equals(code) || "billing_hard_limit_reached".equals(code))) {
            throw failure(Kind.CONFIGURATION, Reason.BILLING_OR_QUOTA);
        }
        if (status == 429 && ("rate_limit_exceeded".equals(code) || "slow_down".equals(code))) {
            throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.RATE_LIMIT,
                    retryAfter(retryHeader));
        }
        if (status == 503 && "server_is_overloaded".equals(code)) {
            throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.OVERLOADED,
                    retryAfter(retryHeader));
        }
        if (status >= 300 && status < 400) {
            throw failure(Kind.CONFIGURATION, Reason.REDIRECT);
        }
        if (status >= 400 && status < 500 && status != 408 && status != 429) {
            throw failure(Kind.CONFIGURATION, Reason.REQUEST_REJECTED);
        }
        if (status != 200) {
            throw failure(Kind.OUTCOME_UNKNOWN, Reason.SERVER_ERROR);
        }
        if (root == null || !Set.of("id", "object", "created", "model", "choices", "usage",
                "service_tier", "system_fingerprint", "moderation").containsAll(root.propertyNames())) {
            // Do not feed unknown outer metadata to Spring AI's payload-bearing logging path.
            throw failure(Kind.INVALID_RESPONSE, Reason.MALFORMED_ENVELOPE);
        }
        JsonNode choices = root == null ? null : root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() != 1 || !choices.get(0).isObject()) {
            // Spring AI logs the Prompt when choices is empty; block it before that path.
            throw failure(Kind.INVALID_RESPONSE, Reason.MALFORMED_ENVELOPE);
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.get("message");
        if (message == null || !message.isObject()) {
            throw failure(Kind.INVALID_RESPONSE, Reason.MALFORMED_ENVELOPE);
        }
        String refusal = text(message.get("refusal"));
        String finish = text(choice.get("finish_reason"));
        if ((refusal != null && !refusal.isBlank()) || "content_filter".equals(finish)) {
            throw failure(Kind.REFUSED, Reason.REFUSAL);
        }
        if (!"stop".equals(finish)) {
            throw failure(Kind.INVALID_RESPONSE, Reason.INCOMPLETE);
        }
        if (!"assistant".equals(text(message.get("role"))) || text(message.get("content")) == null
                || message.hasNonNull("tool_calls") || message.hasNonNull("function_call")) {
            throw failure(Kind.INVALID_RESPONSE, Reason.MALFORMED_ENVELOPE);
        }
    }

    private Duration retryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            if (header.matches("[0-9]+")) {
                return Duration.ofSeconds(Long.parseLong(header));
            }
            Duration wait = Duration.between(clock.instant(),
                    ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (RuntimeException exception) {
            // Missing/invalid hints are not converted to permission for immediate retry.
            return null;
        }
    }

    private static JsonNode parseEnvelope(byte[] bytes) {
        try {
            JsonNode root = MAPPER.readTree(bytes);
            return root != null && root.isObject() ? root : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static TokenUsage usage(JsonNode envelope) {
        JsonNode value = envelope == null ? null : envelope.get("usage");
        if (value == null || !value.isObject()) {
            return null;
        }
        JsonNode input = value.get("prompt_tokens");
        JsonNode output = value.get("completion_tokens");
        if (input == null || output == null || !input.isIntegralNumber() || !output.isIntegralNumber()
                || !input.canConvertToLong() || !output.canConvertToLong()
                || input.longValue() < 0 || output.longValue() < 0) {
            return null;
        }
        return new TokenUsage(input.longValue(), output.longValue());
    }

    private static boolean expectedTariff(JsonNode envelope) {
        String model = envelope == null ? null : text(envelope.get("model"));
        return model != null && model.matches("gpt-6-luna(?:-[0-9]{4}-[0-9]{2}-[0-9]{2})?")
                && "default".equals(text(envelope.get("service_tier")));
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static AiProviderFailureException failure(Kind kind, Reason reason) {
        return new AiProviderFailureException(kind, reason, null);
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
        // This Adapter has no streaming/async HTTP path. Job execution is independently async.
        return CompletableFuture.failedFuture(failure(Kind.CONFIGURATION, Reason.REQUEST_REJECTED));
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private record BufferedResponse(int statusCode, Headers headers, byte[] bytes) implements HttpResponse {
        @Override
        public InputStream body() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return "BufferedResponse[status=" + statusCode + ", content omitted]";
        }
    }
}
