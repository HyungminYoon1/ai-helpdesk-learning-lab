package lab.helpdesk.ai.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Paid one-inquiry fixture; excluded from default Surefire *Test naming patterns. */
@EnabledIfEnvironmentVariable(named = "HELPDESK_AI_LIVE_CONFIRMED", matches = "true")
class SpringAiOpenAiWorkerBrowserLiveExperiment {
    @Test
    void browser_receipt_is_processed_by_the_scheduled_live_worker_then_read_by_agent() {
        Week7WorkerBrowserExperiment.run(true);
    }
}
