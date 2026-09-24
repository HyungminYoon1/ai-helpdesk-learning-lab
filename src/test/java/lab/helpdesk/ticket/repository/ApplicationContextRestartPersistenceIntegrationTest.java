package lab.helpdesk.ticket.repository;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import lab.helpdesk.HelpdeskApplication;
import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketStatus;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ApplicationContextRestartPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void ticket_remains_after_spring_application_context_restart() {
        long ticketId;
        TicketRepository firstRepository;

        try (ConfigurableApplicationContext firstApplication =
                startApplication()) {

            firstRepository = firstApplication.getBean(
                    TicketRepository.class);
            ticketId = firstRepository.save(
                    new Ticket("재시작 영속성"));
        }

        try (ConfigurableApplicationContext secondApplication =
                startApplication()) {

            TicketRepository secondRepository =
                    secondApplication.getBean(
                            TicketRepository.class);

            assertThat(secondRepository)
                    .isNotSameAs(firstRepository);
            assertThat(secondRepository.findById(ticketId))
                    .get()
                    .satisfies(restored -> {
                        assertThat(restored.title())
                                .isEqualTo("재시작 영속성");
                        assertThat(restored.status())
                                .isEqualTo(TicketStatus.OPEN);
                    });
        }
    }

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(
                HelpdeskApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("postgres")
                .properties(Map.of(
                        "server.port", "0",
                        "spring.datasource.url", POSTGRES.getJdbcUrl(),
                        "spring.datasource.username", POSTGRES.getUsername(),
                        "spring.datasource.password", POSTGRES.getPassword()))
                .run();
    }
}
