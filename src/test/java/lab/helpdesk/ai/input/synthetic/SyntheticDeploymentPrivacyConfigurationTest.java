package lab.helpdesk.ai.input.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiPrivacyScanResultValidator;
import lab.helpdesk.ai.input.AiPrivacyScanner;
import lab.helpdesk.ai.input.AiScannedInputPreparer;
import lab.helpdesk.ai.processing.AiSuggestionJobWorker;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SyntheticDeploymentPrivacyConfigurationTest {
    @TempDir Path temporary;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SyntheticDeploymentPrivacyConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "postgres,deployment", "postgres,synthetic-ai", "deployment,synthetic-ai", "in-memory"})
    void incompleteProfilesDoNotProvideAPrivacyFallback(String profiles) {
        runner.withPropertyValues("spring.profiles.active=" + profiles, "helpdesk.ai.synthetic-scan.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AiScannedInputPreparer.class));
    }

    @Test
    void allProfilesWithoutOptInStillLeavePrivacyDisabled() {
        runner.withPropertyValues("spring.profiles.active=postgres,deployment,synthetic-ai")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AiPrivacyScanner.class));
    }

    @Test
    void explicitFalseStillLeavesPrivacyDisabled() {
        enabled().withPropertyValues("helpdesk.ai.synthetic-scan.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AiPrivacyScanner.class));
    }

    @Test
    void missingJarFailsAtAssemblyNotAtTheFirstJob() {
        enabled().run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("HELPDESK_SYNTHETIC_SCAN_JAR_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative.jar", "SYNTHETIC_SECRET_VALUE", "invalid\u0000path"})
    void invalidPathsAreNotRetainedInExceptions(String value) {
        enabled().withPropertyValues("helpdesk.ai.synthetic-scan.scanner-jar=" + value).run(context -> {
            assertThat(context).hasFailed().getFailure().hasRootCauseMessage("HELPDESK_SYNTHETIC_SCAN_JAR_INVALID");
            assertThat(context.getStartupFailure()).hasStackTraceContaining("HELPDESK_SYNTHETIC_SCAN_JAR_INVALID")
                    .hasStackTraceContaining("SyntheticDeploymentPrivacyConfiguration")
                    .hasMessageNotContaining("SYNTHETIC_SECRET_VALUE");
        });
    }

    @Test
    void missingCorruptAndOldJarsFailWithoutReadingAJob() throws Exception {
        Path corrupt = temporary.resolve("corrupt.jar");
        Files.write(corrupt, new byte[] {1, 2, 3});
        Path old = temporary.resolve("old.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(old))) { }
        for (Path jar : new Path[] {temporary.resolve("missing.jar"), corrupt, old}) {
            enabled().withPropertyValues("helpdesk.ai.synthetic-scan.scanner-jar=" + jar).run(context ->
                    assertThat(context).hasFailed().getFailure().hasRootCauseMessage("HELPDESK_SYNTHETIC_SCAN_JAR_INVALID"));
        }
    }

    @Test
    void missingTimeLimitsAreNotSilentlyDefaulted() throws Exception {
        enabled().withPropertyValues(jarProperty()).run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("HELPDESK_SYNTHETIC_SCAN_TIME_LIMIT_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "60001", "SYNTHETIC_SECRET_VALUE", "999999999999999999"})
    void malformedOrExcessiveTimeoutsHaveSanitizedFailures(String value) throws Exception {
        enabled().withPropertyValues(jarProperty(), "helpdesk.ai.synthetic-scan.timeout-ms=" + value,
                "helpdesk.ai.synthetic-scan.cleanup-timeout-ms=3000").run(context -> {
            assertThat(context).hasFailed().getFailure().hasRootCauseMessage("HELPDESK_SYNTHETIC_SCAN_TIME_LIMIT_INVALID");
            assertThat(context.getStartupFailure()).hasMessageNotContaining("SYNTHETIC_SECRET_VALUE");
        });
    }

    @Test
    void excessiveCleanupLimitIsAlsoRejected() throws Exception {
        enabled().withPropertyValues(jarProperty(), "helpdesk.ai.synthetic-scan.timeout-ms=20000",
                "helpdesk.ai.synthetic-scan.cleanup-timeout-ms=10001").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage("HELPDESK_SYNTHETIC_SCAN_TIME_LIMIT_INVALID"));
    }

    @Test
    void suppliesOnlyPrivacyBeansWithoutStartingTheChildProviderOrWorker() throws Exception {
        // Empty class entries suffice for assembly; executing the packaged child is a separate IT.
        enabled().withPropertyValues(jarProperty(), "helpdesk.ai.synthetic-scan.timeout-ms=20000",
                "helpdesk.ai.synthetic-scan.cleanup-timeout-ms=3000").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AiPrivacyScanner.class)
                    .hasSingleBean(AiPrivacyScanResultValidator.class).hasSingleBean(AiInputPrivacyGuard.class)
                    .hasSingleBean(AiScannedInputPreparer.class)
                    .doesNotHaveBean(AiSuggestionProvider.class).doesNotHaveBean(AiSuggestionJobWorker.class);
            assertThat(context.getBean(AiPrivacyScanner.class)).isInstanceOf(SyntheticDeploymentPrivacyScanner.class);
        });
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("spring.profiles.active=postgres,deployment,synthetic-ai",
                "helpdesk.ai.synthetic-scan.enabled=true");
    }

    private String jarProperty() throws Exception {
        Path jar = Files.createTempFile(temporary, "assembly-only-", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : new String[] {SyntheticDeploymentPrivacyConfiguration.PROGRAM_ENTRY,
                    "org/springframework/boot/loader/launch/PropertiesLauncher.class"}) {
                output.putNextEntry(new JarEntry(name));
                output.closeEntry();
            }
        }
        return "helpdesk.ai.synthetic-scan.scanner-jar=" + jar;
    }
}
