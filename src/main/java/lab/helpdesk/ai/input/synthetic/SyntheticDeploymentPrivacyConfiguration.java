package lab.helpdesk.ai.input.synthetic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.JarFile;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiPrivacyScanResultValidator;
import lab.helpdesk.ai.input.AiPrivacyScanner;
import lab.helpdesk.ai.input.AiScannedInputPreparer;
import lab.helpdesk.ai.input.ProcessAiPrivacyScanner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Opt-in synthetic experiment only. Provider and Worker retain their independent switches. */
@Configuration(proxyBeanMethods = false)
@Profile("postgres & deployment & synthetic-ai")
@ConditionalOnProperty(prefix = "helpdesk.ai.synthetic-scan", name = "enabled", havingValue = "true")
public class SyntheticDeploymentPrivacyConfiguration {
    static final String PREFIX = "helpdesk.ai.synthetic-scan.";
    static final String PROGRAM_ENTRY = "BOOT-INF/classes/"
            + SyntheticPrivacyScannerProgram.class.getName().replace('.', '/') + ".class";
    private static final String LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";
    private static final int RESPONSE_BYTES = 1_024;

    @Bean
    SyntheticScanSettings syntheticScanSettings(Environment environment) {
        String value = environment.getProperty(PREFIX + "scanner-jar");
        if (value == null || value.isBlank()) throw invalid("JAR_REQUIRED");
        Path jar;
        try {
            jar = Path.of(value);
            if (!jar.isAbsolute() || !Files.isRegularFile(jar) || !Files.isReadable(jar)) throw invalid("JAR_INVALID");
            try (var archive = new JarFile(jar.toFile())) {
                if (archive.getJarEntry(PROGRAM_ENTRY) == null
                        || archive.getJarEntry(LAUNCHER.replace('.', '/') + ".class") == null) throw invalid("JAR_INVALID");
            }
        } catch (IOException | RuntimeException exception) {
            throw invalid("JAR_INVALID"); // A misplaced secret/path must not appear in messages or causes.
        }
        return new SyntheticScanSettings(jar, duration(environment, "timeout-ms", 60_000),
                duration(environment, "cleanup-timeout-ms", 10_000));
    }

    @Bean
    AiPrivacyScanner aiPrivacyScanner(SyntheticScanSettings settings) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().toString();
        var command = List.of(java, "-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=1", "-Dfile.encoding=UTF-8",
                "-Dloader.main=" + SyntheticPrivacyScannerProgram.class.getName(), "-cp", settings.jar().toString(), LAUNCHER);
        var process = new ProcessAiPrivacyScanner(command, SyntheticPrivacyScannerProgram.MAX_INPUT_BYTES,
                RESPONSE_BYTES, 1_024, settings.cleanupTimeout());
        return new SyntheticDeploymentPrivacyScanner(process);
    }

    @Bean
    AiInputPrivacyGuard aiInputPrivacyGuard() {
        return new AiInputPrivacyGuard(List.of(new SensitiveFragment(SyntheticDeploymentPrivacyScanner.EMAIL, SensitiveType.EMAIL)));
    }

    @Bean
    AiPrivacyScanResultValidator aiPrivacyScanResultValidator() {
        return new AiPrivacyScanResultValidator(RESPONSE_BYTES, 1);
    }

    @Bean
    AiScannedInputPreparer aiScannedInputPreparer(AiPrivacyScanner scanner, AiPrivacyScanResultValidator validator,
            AiInputPrivacyGuard guard, SyntheticScanSettings settings) {
        return new AiScannedInputPreparer(scanner, validator, guard, settings.timeout());
    }

    private Duration duration(Environment environment, String name, int maximum) {
        String value = environment.getProperty(PREFIX + name);
        if (value == null || value.isBlank()) throw invalid("TIME_LIMIT_REQUIRED");
        try {
            int millis = Integer.parseInt(value);
            if (millis < 1 || millis > maximum) throw invalid("TIME_LIMIT_INVALID");
            return Duration.ofMillis(millis);
        } catch (RuntimeException exception) {
            throw invalid("TIME_LIMIT_INVALID");
        }
    }

    private static IllegalStateException invalid(String suffix) {
        return new IllegalStateException("HELPDESK_SYNTHETIC_SCAN_" + suffix);
    }

    record SyntheticScanSettings(Path jar, Duration timeout, Duration cleanupTimeout) {
        @Override public String toString() { return "SyntheticScanSettings[values omitted]"; }
    }
}
