package lab.helpdesk.ticket.application;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketMessage;
import lab.helpdesk.ticket.TicketStatus;
import lab.helpdesk.ticket.repository.JdbcTicketMessageRepository;
import lab.helpdesk.ticket.repository.JdbcTicketRepository;
import lab.helpdesk.ticket.repository.TicketMessageRepository;
import lab.helpdesk.ticket.repository.TicketRepository;
import lab.helpdesk.ai.job.JdbcAiSuggestionJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers
@Import(TicketReceiptIntegrationTest.RecordingConfiguration.class)
class TicketReceiptIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired
    private TicketReceiptApplicationService receipts;

    @Autowired
    private RecordingTicketRepository tickets;

    @Autowired
    private RecordingMessageRepository messages;

    @Autowired
    private JdbcAiSuggestionJobRepository jobs;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearIsolatedTestDatabase() {
        Boolean connectedToTestContainer = jdbcTemplate.execute(
                (ConnectionCallback<Boolean>) connection ->
                        connection.getMetaData().getURL().startsWith(
                                POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        assertThat(connectedToTestContainer).isTrue();

        jdbcTemplate.update("DELETE FROM ai_suggestion_jobs");
        jdbcTemplate.update("DELETE FROM ticket_messages");
        jdbcTemplate.update("DELETE FROM tickets");
        tickets.successfulSaves.set(0);
        messages.successfulSaves.set(0);
    }

    @Test
    void receipt_commits_ticket_original_message_and_pending_job_together() {
        String originalBody = "  로그인 링크가 만료됐습니다.\n새 링크를 요청합니다.  ";

        TicketReceiptResult result = receipts.receive(
                "로그인 문의", originalBody, "synthetic-author");

        assertThat(result.ticket().id()).isPositive();
        assertThat(result.messageId()).isPositive();
        assertThat(result.jobId()).isPositive();
        assertThat(result.ticket().status()).isEqualTo(TicketStatus.OPEN);
        assertRowCounts(1, 1, 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body FROM ticket_messages WHERE id = ?",
                String.class, result.messageId())).isEqualTo(originalBody);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT author_username FROM ticket_messages WHERE id = ?",
                String.class, result.messageId())).isEqualTo("synthetic-author");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ticket_id FROM ticket_messages WHERE id = ?",
                Long.class, result.messageId())).isEqualTo(result.ticket().id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT input_message_id FROM ai_suggestion_jobs WHERE id = ?",
                Long.class, result.jobId())).isEqualTo(result.messageId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_suggestion_jobs WHERE id = ?",
                String.class, result.jobId())).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void blank_message_is_rejected_before_any_insert(String body) {
        assertThatThrownBy(() -> receipts.receive(
                "로그인 문의", body, "synthetic-author"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(tickets.successfulSaves.get()).isZero();
        assertRowCounts(0, 0, 0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void missing_author_is_rejected_before_any_insert(String author) {
        assertThatThrownBy(() -> receipts.receive(
                "로그인 문의", "새 로그인 링크를 요청합니다.", author))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(tickets.successfulSaves.get()).isZero();
        assertRowCounts(0, 0, 0);
    }

    @Test
    void message_insert_failure_rolls_back_previously_successful_ticket_insert() {
        jdbcTemplate.execute("""
                ALTER TABLE ticket_messages
                ADD CONSTRAINT receipt_test_reject_message CHECK (false) NOT VALID
                """);
        try {
            assertThatThrownBy(() -> receipts.receive(
                    "로그인 문의", "새 로그인 링크를 요청합니다.", "synthetic-author"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // 실제 JDBC INSERT가 ID를 반환한 뒤 두 번째 저장에서 실패했다.
            assertThat(tickets.successfulSaves.get()).isOne();
            assertThat(messages.successfulSaves.get()).isZero();
            assertRowCounts(0, 0, 0);
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE ticket_messages
                    DROP CONSTRAINT receipt_test_reject_message
                    """);
        }
    }

    @Test
    void job_insert_failure_rolls_back_successful_ticket_and_message_inserts() {
        jdbcTemplate.execute("""
                ALTER TABLE ai_suggestion_jobs
                ADD CONSTRAINT receipt_test_reject_job CHECK (false) NOT VALID
                """);
        try {
            assertThatThrownBy(() -> receipts.receive(
                    "로그인 문의", "새 로그인 링크를 요청합니다.", "synthetic-author"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(tickets.successfulSaves.get()).isOne();
            assertThat(messages.successfulSaves.get()).isOne();
            assertRowCounts(0, 0, 0);
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE ai_suggestion_jobs
                    DROP CONSTRAINT receipt_test_reject_job
                    """);
        }
    }

    @Test
    void same_message_cannot_have_a_second_initial_job() {
        TicketReceiptResult result = receipts.receive(
                "로그인 문의", "새 로그인 링크를 요청합니다.", "synthetic-author");

        assertThatThrownBy(() -> jobs.enqueue(result.messageId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertRowCounts(1, 1, 1);
    }

    @Test
    void message_cannot_reference_a_missing_ticket() {
        assertThatThrownBy(() -> messages.save(
                Long.MAX_VALUE, new TicketMessage("문의 원문", "synthetic-author")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertRowCounts(0, 0, 0);
    }

    @Test
    void job_cannot_reference_a_missing_message() {
        assertThatThrownBy(() -> jobs.enqueue(Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertRowCounts(0, 0, 0);
    }

    private void assertRowCounts(long ticketCount, long messageCount, long jobCount) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tickets", Long.class)).isEqualTo(ticketCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_messages", Long.class)).isEqualTo(messageCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_suggestion_jobs", Long.class)).isEqualTo(jobCount);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingConfiguration {

        @Bean
        @Primary
        RecordingTicketRepository recordingTickets(JdbcTicketRepository delegate) {
            return new RecordingTicketRepository(delegate);
        }

        @Bean
        @Primary
        RecordingMessageRepository recordingMessages(JdbcTicketMessageRepository delegate) {
            return new RecordingMessageRepository(delegate);
        }
    }

    // 저장을 대체하지 않고 실제 PostgreSQL 저장이 성공해서 반환된 횟수만 기록한다.
    static class RecordingTicketRepository implements TicketRepository {

        private final JdbcTicketRepository delegate;
        final AtomicInteger successfulSaves = new AtomicInteger();

        RecordingTicketRepository(JdbcTicketRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public long save(Ticket ticket) {
            long id = delegate.save(ticket);
            successfulSaves.incrementAndGet();
            return id;
        }

        @Override
        public Optional<Ticket> findById(long id) {
            return delegate.findById(id);
        }
    }

    static class RecordingMessageRepository implements TicketMessageRepository {

        private final JdbcTicketMessageRepository delegate;
        final AtomicInteger successfulSaves = new AtomicInteger();

        RecordingMessageRepository(JdbcTicketMessageRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public long save(long ticketId, TicketMessage message) {
            long id = delegate.save(ticketId, message);
            successfulSaves.incrementAndGet();
            return id;
        }
    }
}
