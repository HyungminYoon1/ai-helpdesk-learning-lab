package lab.helpdesk.ticket.application;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketMessage;
import lab.helpdesk.ticket.TicketStatus;
import lab.helpdesk.ticket.repository.JdbcTicketMessageRepository;
import lab.helpdesk.ticket.repository.JdbcTicketRepository;
import lab.helpdesk.ticket.repository.TicketMessageRepository;
import lab.helpdesk.ticket.repository.TicketReceiptRepository;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@lab.helpdesk.identity.WithIdentityFixtures
@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

    @Autowired
    private PlatformTransactionManager transactionManager;

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
                "로그인 문의", originalBody, lab.helpdesk.identity.IdentityFixtures.actor("synthetic-author"));

        assertThat(result.created()).isTrue();
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
                "로그인 문의", body, lab.helpdesk.identity.IdentityFixtures.actor("synthetic-author")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(tickets.successfulSaves.get()).isZero();
        assertRowCounts(0, 0, 0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void missing_author_is_rejected_before_any_insert(String author) {
        assertThatThrownBy(() -> receipts.receive(
                "로그인 문의", "새 로그인 링크를 요청합니다.", lab.helpdesk.identity.IdentityFixtures.actor(author)))
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
                    "로그인 문의", "새 로그인 링크를 요청합니다.", lab.helpdesk.identity.IdentityFixtures.actor("synthetic-author")))
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
                    "로그인 문의", "새 로그인 링크를 요청합니다.", lab.helpdesk.identity.IdentityFixtures.actor("synthetic-author")))
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
                "로그인 문의", "새 로그인 링크를 요청합니다.", lab.helpdesk.identity.IdentityFixtures.actor("synthetic-author"));

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

    @Test
    void replay_compares_the_original_title_and_body_and_uses_the_authenticated_owner_scope() {
        var firstCustomer = lab.helpdesk.identity.IdentityFixtures.actor("receipt-user-a");
        var secondCustomer = lab.helpdesk.identity.IdentityFixtures.actor("receipt-agent-b");
        UUID key = UUID.randomUUID();
        String title = "접수 확인";
        String body = "  원본 문의\n두 번째 줄  ";

        var first = receipts.receive(title, body, key, firstCustomer);
        // A later status change does not turn a receipt confirmation into a new OPEN ticket.
        jdbcTemplate.update("UPDATE tickets SET status = 'IN_PROGRESS' WHERE id = ?", first.ticket().id());
        var replay = receipts.receive(title, body, key, firstCustomer);
        assertThat(replay.created()).isFalse();
        assertThat(replay.ticket().id()).isEqualTo(first.ticket().id());
        assertThat(replay.ticket().status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(replay.messageId()).isEqualTo(first.messageId());
        assertThat(replay.jobId()).isEqualTo(first.jobId());

        assertThatThrownBy(() -> receipts.receive(title + " ", body, key, firstCustomer))
                .isInstanceOf(TicketReceiptConflictException.class);
        assertThatThrownBy(() -> receipts.receive(title, body.strip(), key, firstCustomer))
                .isInstanceOf(TicketReceiptConflictException.class);
        assertRowCounts(1, 1, 1);
        assertThat(jdbcTemplate.queryForObject("SELECT body FROM ticket_messages WHERE id = ?",
                String.class, first.messageId())).isEqualTo(body);

        var anotherOwner = receipts.receive(title, body, key, secondCustomer);
        var separateSubmission = receipts.receive(title, body, UUID.randomUUID(), firstCustomer);
        assertThat(anotherOwner.created()).isTrue();
        assertThat(separateSubmission.created()).isTrue();
        assertThat(java.util.Set.of(first.ticket().id(), anotherOwner.ticket().id(), separateSubmission.ticket().id()))
                .hasSize(3);
        assertRowCounts(3, 3, 3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void overlapping_submissions_wait_for_the_same_key_and_follow_commit_or_rollback(boolean rollBackFirst)
            throws Exception {
        var customer = lab.helpdesk.identity.IdentityFixtures.actor("receipt-user-a");
        UUID key = UUID.randomUUID();
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(20);
        var firstReady = new CountDownLatch(1);
        var finishFirst = new CountDownLatch(1);
        var firstPid = new CompletableFuture<Integer>();
        var secondPid = new CompletableFuture<Integer>();

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> transaction.execute(status -> {
                firstPid.complete(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                var result = receipts.receive("동시 접수", "원본 문의", key, customer);
                firstReady.countDown();
                await(finishFirst);
                if (rollBackFirst) {
                    status.setRollbackOnly();
                }
                return result;
            }));
            try {
                await(firstReady);
                var second = workers.submit(() -> transaction.execute(status -> {
                    secondPid.complete(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return receipts.receive("동시 접수", "원본 문의", key, customer);
                }));
                awaitDatabaseBlock(firstPid.get(8, TimeUnit.SECONDS), secondPid.get(8, TimeUnit.SECONDS));
                assertRowCounts(0, 0, 0);

                finishFirst.countDown();
                var initial = first.get(8, TimeUnit.SECONDS);
                var contender = second.get(8, TimeUnit.SECONDS);
                assertThat(initial.created()).isTrue();
                assertThat(contender.created()).isEqualTo(rollBackFirst);
                if (rollBackFirst) {
                    assertThat(contender.ticket().id()).isNotEqualTo(initial.ticket().id());
                } else {
                    assertThat(contender.ticket().id()).isEqualTo(initial.ticket().id());
                    assertThat(contender.messageId()).isEqualTo(initial.messageId());
                    assertThat(contender.jobId()).isEqualTo(initial.jobId());
                }
                assertRowCounts(1, 1, 1);
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT id FROM tickets WHERE receipt_request_id = ?", Long.class, key))
                        .isEqualTo(contender.ticket().id());
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT input_message_id FROM ai_suggestion_jobs WHERE status = 'PENDING'", Long.class))
                        .isEqualTo(contender.messageId());
            } finally {
                finishFirst.countDown();
            }
        }
    }

    private void awaitDatabaseBlock(int holder, int waiter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holder, waiter))) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("the contender never waited on the first receipt transaction");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("receipt test was interrupted", interrupted);
        }
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
    static class RecordingTicketRepository implements TicketReceiptRepository {

        private final JdbcTicketRepository delegate;
        final AtomicInteger successfulSaves = new AtomicInteger();

        RecordingTicketRepository(JdbcTicketRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public OptionalLong insertReceipt(Ticket ticket, long requesterUserId, long serviceId, UUID clientRequestId) {
            var inserted = delegate.insertReceipt(ticket, requesterUserId, serviceId, clientRequestId);
            if (inserted.isPresent()) {
                successfulSaves.incrementAndGet();
            }
            return inserted;
        }

        @Override
        public Optional<StoredReceipt> findReceipt(long requesterUserId, UUID clientRequestId) {
            return delegate.findReceipt(requesterUserId, clientRequestId);
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
