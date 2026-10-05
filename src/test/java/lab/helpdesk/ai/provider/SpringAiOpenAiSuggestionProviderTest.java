package lab.helpdesk.ai.provider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.PreparedInput;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.job.AiJobRequestKind;
import lab.helpdesk.ai.provider.AiProviderFailureException.Kind;
import lab.helpdesk.ai.provider.AiProviderFailureException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Real HTTP + Spring AI + SDK, controlled fixture responses; no paid AI calls. */
@ExtendWith(OutputCaptureExtension.class)
class SpringAiOpenAiSuggestionProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);
    private static final String MARKER = "SYNTHETIC_CONTACT_VALUE";
    private static final AiInputPrivacyGuard PRIVACY = new AiInputPrivacyGuard(
            List.of(new SensitiveFragment(MARKER, SensitiveType.CONTACT)));
    private static final String VALID = """
            {"decision":"SUGGEST","summary":"로그인 복구 후 링크 만료 이유를 문의함",
             "categories":["ACCOUNT"],"priority":"NORMAL"}
            """;
    private HttpServer server;
    private SpringAiOpenAiSuggestionProvider adapter;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String capturedRequest;
    private volatile String capturedPath;
    private volatile String fixtureBody;
    private volatile String retryAfter;
    private volatile int fixtureStatus;
    private volatile long delayMs;

    @BeforeEach
    void start_loopback_fixture() throws IOException {
        fixtureStatus = 200;
        fixtureBody = envelope(VALID, "stop", null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            capturedPath = exchange.getRequestURI().getPath();
            capturedRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                if (retryAfter != null) {
                    exchange.getResponseHeaders().add("Retry-After", retryAfter);
                }
                if (fixtureStatus == 307) {
                    exchange.getResponseHeaders().add("Location", baseUrl() + "/unexpected-destination");
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                byte[] bytes = fixtureBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(fixtureStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        adapter = new SpringAiOpenAiSuggestionProvider("synthetic-fixture-credential", PRIVACY,
                200, baseUrl(), CLOCK);
    }

    @AfterEach
    void close_clients_and_fixture() {
        if (adapter != null) {
            adapter.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void each_call_sends_the_checked_copy_once_with_structured_output_and_explicit_settings() {
        String original = "새 링크로 로그인에 성공했습니다. " + MARKER;
        String output = adapter.generate(PRIVACY.prepare("로그인 문의", original), Duration.ofSeconds(2),
                AiJobRequestKind.INITIAL);
        JsonNode request = MAPPER.readTree(capturedRequest);
        JsonNode user = MAPPER.readTree(request.get("messages").get(1).get("content").asString());

        assertThat(output).isEqualTo(VALID);
        assertThat(requests).hasValue(1);
        assertThat(capturedPath).isEqualTo("/v1/chat/completions");
        assertThat(user.propertyNames()).containsExactlyInAnyOrder("title", "body");
        assertThat(user.get("body").asString()).isEqualTo("새 링크로 로그인에 성공했습니다. [CONTACT_REDACTED]");
        assertThat(original).contains(MARKER);
        assertThat(capturedRequest).doesNotContain(MARKER, "ticketId", "messageId", "expectedPriority");
        assertThat(request.get("model").asString()).isEqualTo("gpt-6-luna");
        assertThat(request.get("store").asBoolean()).isFalse();
        assertThat(request.get("n").asInt()).isOne();
        assertThat(request.get("max_completion_tokens").asInt()).isEqualTo(600);
        assertThat(request.get("reasoning_effort").asString()).isEqualTo("none");
        assertThat(request.get("service_tier").asString()).isEqualTo("default");
        assertThat(request.get("messages").get(0).get("content").asString())
                .contains("trim 후 1~200 Unicode Code Point");
        JsonNode format = request.get("response_format");
        assertThat(format.get("type").asString()).isEqualTo("json_schema");
        assertThat(format.get("json_schema").get("strict").asBoolean()).isTrue();
        assertThat(format.get("json_schema").get("schema").get("additionalProperties").asBoolean()).isFalse();
        assertThat(adapter.lastCallEvidence()).isEqualTo(new AiProviderCallEvidence(1, 200,
                new AiProviderCallEvidence.TokenUsage(120, 40), true));
        // A second explicit Port invocation is allowed; it has its own one-attempt evidence.
        adapter.generate(prepared(), Duration.ofSeconds(2), AiJobRequestKind.INITIAL);
        assertThat(requests).hasValue(2);
        assertThat(adapter.lastCallEvidence().httpAttempts()).isOne();
    }

    @Test
    void checks_the_actual_serialized_body_even_if_a_caller_bypasses_prepare() {
        AiProviderFailureException failure = failure(() -> adapter.generate(
                new PreparedInput("문의", MARKER), Duration.ofSeconds(2), AiJobRequestKind.INITIAL));

        assertThat(failure.kind()).isEqualTo(Kind.CONFIGURATION);
        assertThat(requests).hasValue(0);
        assertThat(adapter.lastCallEvidence().httpAttempts()).isZero();
    }

    @Test
    void rejects_sensitive_values_inside_nested_json_escapes_before_transmission() {
        failure(() -> adapter.generate(new PreparedInput("문의", "SYNTHETIC_CONTACT_VALUE"),
                Duration.ofSeconds(2), AiJobRequestKind.INITIAL));
        assertThat(requests).hasValue(0);
    }

    @Test
    void the_api_credential_cannot_appear_in_model_input_even_when_not_configured_as_a_marker() {
        failure(() -> adapter.generate(new PreparedInput("문의", "synthetic-fixture-credential"),
                Duration.ofSeconds(2), AiJobRequestKind.INITIAL));
        assertThat(requests).hasValue(0);
    }

    @Test
    void an_output_repair_adds_only_a_fixed_instruction_not_the_prior_output_or_identifiers() {
        adapter.generate(prepared(), Duration.ofSeconds(2), AiJobRequestKind.OUTPUT_REPAIR);
        assertThat(MAPPER.readTree(capturedRequest).get("messages").get(0).get("content").asString())
                .contains("필수 필드가 누락됐다");
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"401,invalid_api_key,CONFIGURATION,AUTHENTICATION",
            "403,permission_denied,CONFIGURATION,AUTHENTICATION",
            "429,insufficient_quota,CONFIGURATION,BILLING_OR_QUOTA",
            "429,billing_hard_limit_reached,CONFIGURATION,BILLING_OR_QUOTA",
            "429,rate_limit_exceeded,TEMPORARY_REJECTION,RATE_LIMIT",
            "429,slow_down,TEMPORARY_REJECTION,RATE_LIMIT",
            "503,server_is_overloaded,TEMPORARY_REJECTION,OVERLOADED",
            "429,unknown_code,OUTCOME_UNKNOWN,SERVER_ERROR",
            "500,server_error,OUTCOME_UNKNOWN,SERVER_ERROR",
            "408,request_timeout,OUTCOME_UNKNOWN,SERVER_ERROR",
            "400,unsupported_parameter,CONFIGURATION,REQUEST_REJECTED"})
    void distinguishes_failure_types_without_hidden_retries(int status, String code, Kind kind, Reason reason) {
        fixtureStatus = status;
        fixtureBody = MAPPER.writeValueAsString(Map.of("error", Map.of("code", code,
                "message", "UNSAFE_PROVIDER_MESSAGE")));
        AiProviderFailureException failure = failure(this::generate);

        assertThat(failure.kind()).isEqualTo(kind);
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.getMessage()).doesNotContain("UNSAFE_PROVIDER_MESSAGE");
        assertThat(requests).hasValue(1);
        assertThat(adapter.lastCallEvidence().httpStatus()).isEqualTo(status);
        assertThat(adapter.lastCallEvidence().usage()).isNull();
    }

    @ParameterizedTest
    @CsvSource(value = {"10|10", "Tue, 6 Oct 2026 00:00:30 GMT|30", "86400|86400"}, delimiter = '|')
    void preserves_a_valid_retry_after_without_capping_or_sleeping(String header, long seconds) {
        // Pipe separates fields so the HTTP-date comma remains part of the first field.
        fixtureStatus = 429;
        fixtureBody = "{\"error\":{\"code\":\"rate_limit_exceeded\"}}";
        retryAfter = header;
        AiProviderFailureException failure = failure(this::generate);
        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(seconds));
        assertThat(requests).hasValue(1);
    }

    @Test
    void malformed_retry_after_is_not_a_zero_wait_retry_permission() {
        fixtureStatus = 429;
        fixtureBody = "{\"error\":{\"code\":\"rate_limit_exceeded\"}}";
        retryAfter = "nonsense";
        assertThat(failure(this::generate).retryAfter()).isEmpty();
        assertThat(requests).hasValue(1);
    }

    @Test
    void redirect_is_not_followed_to_another_endpoint() {
        fixtureStatus = 307;
        fixtureBody = "{}";
        assertThat(failure(this::generate).reason()).isEqualTo(Reason.REDIRECT);
        assertThat(requests).hasValue(1);
        assertThat(capturedPath).isEqualTo("/v1/chat/completions");
    }

    @Test
    void a_timeout_after_server_receipt_has_an_unknown_outcome_not_a_second_call() {
        delayMs = 400;
        AiProviderFailureException failure = failure(() -> adapter.generate(prepared(),
                Duration.ofMillis(80), AiJobRequestKind.INITIAL));
        assertThat(failure.kind()).isEqualTo(Kind.OUTCOME_UNKNOWN);
        assertThat(failure.reason()).isEqualTo(Reason.TRANSPORT);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "tool_calls", "unknown"})
    void incomplete_or_unexpected_finish_reason_is_not_a_complete_output(String reason) {
        fixtureBody = envelope(VALID, reason, null);
        assertThat(failure(this::generate).reason()).isEqualTo(Reason.INCOMPLETE);
        assertThat(requests).hasValue(1);
    }

    @Test
    void a_refusal_is_not_abstain_and_its_message_is_not_retained(CapturedOutput logs) {
        fixtureBody = envelope(null, "stop", "UNSAFE_REFUSAL_TEXT");
        AiProviderFailureException failure = failure(this::generate);
        assertThat(failure.kind()).isEqualTo(Kind.REFUSED);
        assertThat(failure.getMessage()).doesNotContain("UNSAFE_REFUSAL_TEXT");
        assertThat(logs).doesNotContain("UNSAFE_REFUSAL_TEXT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"choices\":[]}", "not json",
            "{\"choices\":[{},{}]}", "{\"choices\":[{\"message\":null}]}"})
    void malformed_envelope_never_reaches_a_prompt_logging_path(String body, CapturedOutput logs) {
        fixtureBody = body;
        assertThat(failure(this::generate).kind()).isEqualTo(Kind.INVALID_RESPONSE);
        assertThat(logs).doesNotContain("PRIVATE_FIXTURE_BODY", "No choices returned for prompt");
        assertThat(requests).hasValue(1);
    }

    @Test
    void unknown_outer_metadata_is_not_passed_to_spring_ai_logging() {
        var root = (tools.jackson.databind.node.ObjectNode) MAPPER.readTree(fixtureBody);
        root.put("untrusted_extra_metadata", "UNSAFE_PROVIDER_TEXT");
        fixtureBody = MAPPER.writeValueAsString(root);
        assertThat(failure(this::generate).kind()).isEqualTo(Kind.INVALID_RESPONSE);
    }

    @Test
    void response_size_is_bounded_before_sdk_deserialization() {
        fixtureBody = " ".repeat(SingleAttemptOpenAiHttpClient.MAX_RESPONSE_BYTES + 1);
        assertThat(failure(this::generate).reason()).isEqualTo(Reason.RESPONSE_TOO_LARGE);
    }

    @Test
    void request_size_is_bounded_before_any_http_request() {
        failure(() -> adapter.generate(new PreparedInput("문의", "가".repeat(20_000)),
                Duration.ofSeconds(2), AiJobRequestKind.INITIAL));
        assertThat(requests).hasValue(0);
    }

    @Test
    void missing_usage_remains_unknown_instead_of_becoming_zero() {
        var root = (tools.jackson.databind.node.ObjectNode) MAPPER.readTree(fixtureBody);
        root.remove("usage");
        fixtureBody = MAPPER.writeValueAsString(root);
        generate();
        assertThat(adapter.lastCallEvidence().usage()).isNull();
    }

    @Test
    void an_unknown_returned_model_does_not_receive_the_expected_model_cost_estimate() {
        var root = (tools.jackson.databind.node.ObjectNode) MAPPER.readTree(fixtureBody);
        root.put("model", "another-model");
        fixtureBody = MAPPER.writeValueAsString(root);
        generate();
        assertThat(adapter.lastCallEvidence().expectedTariffConfirmed()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void absent_explicit_key_never_falls_back_to_a_global_environment_key(String key) {
        assertThatThrownBy(() -> new SpringAiOpenAiSuggestionProvider(key, PRIVACY, 200))
                .isInstanceOf(AiProviderFailureException.class).hasNoCause();
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/v1", "http://api.openai.com/v1",
            "http://localhost:1234/v1", "http://127.0.0.1:1234/v1?key=unsafe",
            "http://127.0.0.1:1234/v1#fragment"})
    void arbitrary_or_ambiguous_endpoints_cannot_receive_credentials(String url) {
        assertThatThrownBy(() -> new SpringAiOpenAiSuggestionProvider("synthetic-fixture-credential",
                PRIVACY, 200, url, CLOCK)).isInstanceOf(AiProviderFailureException.class).hasNoCause();
    }

    private void generate() {
        adapter.generate(prepared(), Duration.ofSeconds(2), AiJobRequestKind.INITIAL);
    }

    private PreparedInput prepared() {
        return PRIVACY.prepare("로그인 문의", "PRIVATE_FIXTURE_BODY 새 링크로 로그인에 성공했습니다.");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private AiProviderFailureException failure(Runnable call) {
        AiProviderFailureException failure = catchThrowableOfType(AiProviderFailureException.class, call::run);
        assertThat(failure).isNotNull().hasNoCause();
        return failure;
    }

    private String envelope(String content, String finish, String refusal) {
        var message = MAPPER.createObjectNode().put("role", "assistant").put("content", content)
                .put("refusal", refusal);
        return MAPPER.writeValueAsString(Map.of("id", "synthetic-completion", "object", "chat.completion",
                "created", 1_791_244_800, "model", "gpt-6-luna",
                "service_tier", "default",
                "choices", List.of(Map.of("index", 0, "finish_reason", finish, "message", message)),
                "usage", Map.of("prompt_tokens", 120, "completion_tokens", 40, "total_tokens", 160)));
    }
}
