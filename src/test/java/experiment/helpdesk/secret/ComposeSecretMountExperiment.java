package experiment.helpdesk.secret;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.processing.AiSuggestionJobWorker;
import lab.helpdesk.ai.processing.AiSuggestionWorkerConfiguration;
import lab.helpdesk.ai.processing.AiSuggestionWorkerScheduler;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.ai.provider.AiSuggestionProviderConfiguration;
import lab.helpdesk.ai.provider.SpringAiOpenAiSuggestionProvider;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only Main mounted into an existing application Image. Never calls generate.
 * Uses the real Provider configuration without scanning the web/database application.
 */
public final class ComposeSecretMountExperiment {

    private static final Path SECRET_FILE = Path.of("/run/secrets/helpdesk.ai.provider.key");
    private static final Path EXPECTED_DIGEST = Path.of("/opt/probe-expectation/key.sha256");
    private static final String KEY_PROPERTY = "helpdesk.ai.provider.key";
    private static final String REQUIRED_KEY_CODE = "HELPDESK_AI_PROVIDER_KEY_REQUIRED";
    private static final String RESULT_PREFIX = "WEEK8_SECRET_PROBE_RESULT:";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ComposeSecretMountExperiment() {
    }

    public static void main(String[] args) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean passed = false;
        try {
            if (args.length != 1 || !List.of("PRESENT", "BLANK", "MISSING", "DISABLED").contains(args[0])) {
                throw new IllegalStateException("PROBE_CASE_INVALID");
            }
            String mode = args[0];
            boolean filePresent = Files.exists(SECRET_FILE);
            boolean fileReadable = Files.isReadable(SECRET_FILE);
            result.put("case", mode);
            result.put("secretFilePresent", filePresent);
            result.put("secretFileReadable", fileReadable);

            SpringApplication application = new SpringApplication(ProbeConfiguration.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setBannerMode(Banner.Mode.OFF);
            application.setLogStartupInfo(false);
            application.setRegisterShutdownHook(false);

            try (ConfigurableApplicationContext context = application.run()) {
                boolean providerPresent = !context.getBeansOfType(AiSuggestionProvider.class).isEmpty();
                boolean workerPresent = !context.getBeansOfType(AiSuggestionJobWorker.class).isEmpty();
                boolean schedulerPresent = !context.getBeansOfType(AiSuggestionWorkerScheduler.class).isEmpty();
                boolean dataSourcePresent = !context.getBeansOfType(DataSource.class).isEmpty();
                result.put("startupAccepted", true);
                result.put("providerRegistered", providerPresent);
                result.put("workerRegistered", workerPresent);
                result.put("schedulerRegistered", schedulerPresent);
                result.put("dataSourceRegistered", dataSourcePresent);

                if ("PRESENT".equals(mode)) {
                    String fileValue = Files.readString(SECRET_FILE, StandardCharsets.UTF_8);
                    String expectedDigest = Files.readString(EXPECTED_DIGEST, StandardCharsets.UTF_8).strip();
                    String actualDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(fileValue.getBytes(StandardCharsets.UTF_8)));
                    boolean fixtureMatches = actualDigest.equals(expectedDigest);
                    // Compare booleans only; never serialize the value, digest, or Environment.
                    boolean configuredValueMatches = fileValue.equals(context.getEnvironment().getProperty(KEY_PROPERTY));
                    SpringAiOpenAiSuggestionProvider provider = context.getBean(SpringAiOpenAiSuggestionProvider.class);
                    boolean consumerWired = context.getBean(ProviderConsumer.class).provider() == provider;
                    int httpAttempts = provider.lastCallEvidence().httpAttempts();
                    result.put("fixtureMatchesExpectation", fixtureMatches);
                    result.put("configuredValueMatchesFixture", configuredValueMatches);
                    result.put("providerConsumerWired", consumerWired);
                    result.put("httpAttempts", httpAttempts);
                    passed = fileReadable && fixtureMatches && configuredValueMatches && consumerWired
                            && providerPresent && context.getBeansOfType(AiSuggestionOutputValidator.class).size() == 1
                            && !workerPresent && !schedulerPresent && !dataSourcePresent && httpAttempts == 0;
                } else if ("DISABLED".equals(mode)) {
                    boolean consumerAbsent = context.getBeansOfType(ProviderConsumer.class).isEmpty();
                    boolean validatorAbsent = context.getBeansOfType(AiSuggestionOutputValidator.class).isEmpty();
                    boolean keyAbsent = !context.getEnvironment().containsProperty(KEY_PROPERTY);
                    result.put("providerConsumerAbsent", consumerAbsent);
                    result.put("configuredKeyAbsent", keyAbsent);
                    result.put("httpAttempts", 0);
                    passed = !filePresent && keyAbsent && !providerPresent && consumerAbsent && validatorAbsent
                            && !workerPresent && !schedulerPresent && !dataSourcePresent;
                }
            } catch (RuntimeException failure) {
                Throwable root = failure;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                boolean expectedKeyFailure = root instanceof IllegalStateException
                        && REQUIRED_KEY_CODE.equals(root.getMessage());
                result.put("startupAccepted", false);
                result.put("requiredKeyRejected", expectedKeyFailure);
                // Do not retain or print raw exception messages, causes, or stack traces.
                result.put("failureCode", expectedKeyFailure ? REQUIRED_KEY_CODE : "PROBE_STARTUP_FAILED");
                passed = expectedKeyFailure && (("BLANK".equals(mode) && fileReadable)
                        || ("MISSING".equals(mode) && !filePresent));
            }
        } catch (Throwable failure) {
            result.put("failureCode", "PROBE_FAILED");
        }
        result.put("outcome", passed ? "PASS" : "FAIL");
        System.out.println(RESULT_PREFIX + MAPPER.writeValueAsString(result));
        if (!passed) {
            System.exit(1);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ AiSuggestionProviderConfiguration.class, AiSuggestionWorkerConfiguration.class,
            ProbeConsumerConfiguration.class })
    static class ProbeConfiguration {

        @Bean
        AiInputPrivacyGuard syntheticPrivacyGuard() {
            // A non-empty test fixture only. Not a PII detector or deployment privacy policy.
            return new AiInputPrivacyGuard(List.of(new SensitiveFragment(
                    "synthetic-probe-contact@example.invalid", SensitiveType.EMAIL)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "helpdesk.ai.provider", name = "enabled", havingValue = "true")
    static class ProbeConsumerConfiguration {

        @Bean
        ProviderConsumer probeConsumer(AiSuggestionProvider provider) {
            return new ProviderConsumer(provider);
        }
    }

    record ProviderConsumer(AiSuggestionProvider provider) {
    }
}
