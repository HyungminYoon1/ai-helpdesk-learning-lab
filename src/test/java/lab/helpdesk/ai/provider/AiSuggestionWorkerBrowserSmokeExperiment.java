package lab.helpdesk.ai.provider;

import org.junit.jupiter.api.Test;

/** Free Browser/Worker plumbing check, explicitly selected instead of the normal Test suite. */
class AiSuggestionWorkerBrowserSmokeExperiment {
    @Test
    void controlled_provider_with_real_browser_scheduled_worker_and_postgres() {
        Week7WorkerBrowserExperiment.run(false);
    }
}
