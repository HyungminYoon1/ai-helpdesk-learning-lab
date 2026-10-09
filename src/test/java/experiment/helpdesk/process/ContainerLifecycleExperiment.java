package experiment.helpdesk.process;

import lab.helpdesk.HelpdeskApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only entry point mounted at runtime, never packaged in the application image. */
public final class ContainerLifecycleExperiment {
    private ContainerLifecycleExperiment() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(HelpdeskApplication.class, ProbeConfiguration.class)
                .profiles("in-memory")
                .run("--server.port=8080",
                        "--helpdesk.ai.provider.enabled=false",
                        "--helpdesk.ai.worker.enabled=false",
                        "--logging.level.root=WARN");
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        SlowRequestController slowRequestController() {
            return new SlowRequestController();
        }
    }

    @RestController
    static class SlowRequestController {
        @GetMapping("/__experiment/slow")
        String slow() throws InterruptedException {
            System.out.println("LIFECYCLE_PROBE_REQUEST_STARTED");
            Thread.sleep(4_000);
            return "SYNTHETIC_REQUEST_COMPLETED";
        }
    }
}
