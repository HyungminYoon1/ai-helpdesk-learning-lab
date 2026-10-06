package lab.helpdesk.ai.web;

import lab.helpdesk.ai.query.AiSuggestionQueryService;
import lab.helpdesk.ai.query.JdbcAiSuggestionQueryRepository;
import lab.helpdesk.ticket.repository.InMemoryTicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("in-memory")
class AiSuggestionQueryProfileIntegrationTest {

    @Autowired private ApplicationContext context;

    @Test
    void in_memory_mode_has_no_postgres_suggestion_query_beans() {
        assertThat(context.getBeansOfType(InMemoryTicketRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(AiSuggestionQueryController.class)).isEmpty();
        assertThat(context.getBeansOfType(AiSuggestionQueryService.class)).isEmpty();
        assertThat(context.getBeansOfType(JdbcAiSuggestionQueryRepository.class)).isEmpty();
    }
}
