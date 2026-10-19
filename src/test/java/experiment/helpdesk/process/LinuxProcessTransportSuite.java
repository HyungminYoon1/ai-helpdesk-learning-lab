package experiment.helpdesk.process;

import java.util.Map;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import tools.jackson.databind.json.JsonMapper;

/** Test-only entry point: run one existing suite without Spring, a DB, keys or a network. */
public final class LinuxProcessTransportSuite {
    private LinuxProcessTransportSuite() { }

    public static void main(String[] args) {
        try {
            if (args.length != 0 || !System.getProperty("os.name").equals("Linux")) {
                throw new IllegalStateException();
            }
            var request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass("lab.helpdesk.ai.input.ProcessAiPrivacyScannerTest"))
                    .build();
            var listener = new SummaryGeneratingListener();
            var launcher = LauncherFactory.create();
            launcher.registerTestExecutionListeners(listener);
            launcher.execute(request);
            var summary = listener.getSummary();
            boolean passed = summary.getTestsFoundCount() >= 24
                    && summary.getTestsFoundCount() == summary.getTestsSucceededCount()
                    && summary.getTestsFailedCount() == 0 && summary.getTestsAbortedCount() == 0
                    && summary.getTestsSkippedCount() == 0 && summary.getContainersFailedCount() == 0;
            // Never print assertion payloads, stderr or exception messages/causes.
            String result = JsonMapper.builder().build().writeValueAsString(Map.of(
                    "evidence", "LINUX_PROCESS_TRANSPORT_SUITE",
                    "testsFound", summary.getTestsFoundCount(),
                    "succeeded", summary.getTestsSucceededCount(),
                    "failed", summary.getTestsFailedCount(),
                    "aborted", summary.getTestsAbortedCount(),
                    "skipped", summary.getTestsSkippedCount(),
                    "containersFailed", summary.getContainersFailedCount(),
                    "failureTypes", summary.getFailures().stream()
                            .map(failure -> failure.getException().getClass().getName()).distinct().toList(),
                    "passed", passed));
            System.out.println("LINUX_PROCESS_TEST_RESULT " + result);
            if (!passed) System.exit(1);
        } catch (Throwable failure) {
            System.err.println("LINUX_PROCESS_TEST_ABORTED");
            System.exit(2);
        }
    }
}
