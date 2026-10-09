package lab.helpdesk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import lab.helpdesk.HelpdeskApplication;
import tools.jackson.databind.json.JsonMapper;

class DeploymentHttpObservationTest {
    @Test
    void realHttpRecordsSecurityDeniedRequestsAsWellAsSuccessfulHealthChecks() throws Exception {
        try (var context = new SpringApplicationBuilder(HelpdeskApplication.class)
                .profiles("in-memory")
                .run("--server.address=127.0.0.1", "--server.port=0",
                        "--helpdesk.ai.provider.enabled=false", "--helpdesk.ai.worker.enabled=false")) {
            int port = context.getEnvironment().getProperty("local.server.port", Integer.class);
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var health = client.send(request(port, "/actuator/health"), HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).isEqualTo(200);
                var json = JsonMapper.builder().build().readTree(health.body());
                assertThat(json.size()).isOne();
                assertThat(json.get("status").asString()).isEqualTo("UP");

                var head = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/actuator/health"))
                        .timeout(Duration.ofSeconds(5)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(head.statusCode()).isEqualTo(200);
                assertThat(head.body()).isEmpty();

                var denied = client.send(request(port, "/api/tickets/999"), HttpResponse.BodyHandlers.discarding());
                assertThat(denied.statusCode()).isEqualTo(401);
                assertThat(denied.headers().firstValue("Location")).isEmpty();

                MeterRegistry meters = context.getBean(MeterRegistry.class);
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    double successful = meters.find("http.server.requests").tag("status", "200")
                            .timers().stream().mapToLong(timer -> timer.count()).sum();
                    double rejected = meters.find("http.server.requests").tag("status", "401")
                            .timers().stream().mapToLong(timer -> timer.count()).sum();
                    assertThat(successful).isGreaterThanOrEqualTo(1);
                    assertThat(rejected).isGreaterThanOrEqualTo(1);
                });
            }
        }
    }

    private HttpRequest request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
    }
}
