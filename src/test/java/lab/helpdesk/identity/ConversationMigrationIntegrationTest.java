package lab.helpdesk.identity;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ConversationMigrationIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v9_preserves_legacy_rows_and_identity_bindings_survive_repository_recreation() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("8").load().migrate();
        long old = jdbc.queryForObject("INSERT INTO tickets(title,status) VALUES ('legacy','IN_PROGRESS') RETURNING id", Long.class);
        long message = jdbc.queryForObject("INSERT INTO ticket_messages(ticket_id,body,author_username) VALUES (?, '  original  ', 'old-name') RETURNING id", Long.class, old);
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id=?", String.class, old)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT requester_user_id IS NULL FROM tickets WHERE id=?", Boolean.class, old)).isTrue();
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id=?", String.class, message)).isEqualTo("  original  ");
        assertThat(jdbc.queryForObject("SELECT message_type FROM ticket_messages WHERE id=?", String.class, message)).isEqualTo("INQUIRY");
        var identities = new JdbcUserIdentityRepository(jdbc);
        UUID key = UUID.randomUUID();
        long id = identities.bind(key, "synthetic-original");
        assertThat(identities.bind(key, "synthetic-renamed")).isEqualTo(id);
        assertThat(new JdbcUserIdentityRepository(jdbc).findId(key)).hasValue(id);
        assertThat(identities.bind(UUID.randomUUID(), "synthetic-original")).isNotEqualTo(id);
        assertThatThrownBy(() -> identities.bind(UUID.randomUUID(), "synthetic-renamed"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ticket_messages(ticket_id,body,author_username,message_type) VALUES (?, 'invalid','agent','OFFICIAL_REPLY')", old))
                .isInstanceOf(DataIntegrityViolationException.class);
        long first = reply(jdbc, old, id, null);
        long other = jdbc.queryForObject("INSERT INTO tickets(title,status) VALUES ('other','OPEN') RETURNING id", Long.class);
        assertThatThrownBy(() -> reply(jdbc, other, id, first)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reply(jdbc, old, id, first)).isGreaterThan(first);
    }

    private long reply(JdbcTemplate jdbc, long ticket, long author, Long correction) {
        return jdbc.queryForObject("""
                INSERT INTO ticket_messages(ticket_id,body,author_username,author_user_id,message_type,client_request_id,corrects_message_id)
                VALUES (?, 'reply','agent',?,'OFFICIAL_REPLY',?,?) RETURNING id
                """, Long.class, ticket, author, UUID.randomUUID(), correction);
    }
}
