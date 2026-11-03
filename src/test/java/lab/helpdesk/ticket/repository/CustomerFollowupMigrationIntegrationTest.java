package lab.helpdesk.ticket.repository;

import java.util.List;
import java.util.UUID;
import lab.helpdesk.identity.JdbcUserIdentityRepository;
import lab.helpdesk.support.JdbcSupportRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CustomerFollowupMigrationIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v11_preserves_v10_rows_and_constrains_customer_message_fields_and_shared_uuid() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        Flyway.configure().dataSource(ds).target("10").load().migrate();
        long author=new JdbcUserIdentityRepository(jdbc).bind(UUID.randomUUID(),"synthetic-followup-author");
        long ticket=jdbc.queryForObject("INSERT INTO tickets(title,status,requester_user_id) VALUES ('legacy','OPEN',?) RETURNING id",Long.class,author);
        jdbc.update("INSERT INTO ticket_messages(ticket_id,body,author_username) VALUES (?,' original ','legacy')",ticket);
        UUID replyKey=UUID.randomUUID();
        long reply=jdbc.queryForObject("INSERT INTO ticket_messages(ticket_id,body,author_username,author_user_id,message_type,client_request_id) VALUES (?,'reply','legacy',?,'OFFICIAL_REPLY',?) RETURNING id",Long.class,ticket,author,replyKey);
        var support=new JdbcSupportRepository(jdbc);
        long service=support.registerService("followup-migration","Synthetic service");
        support.registerMember(service,author,"Synthetic author","AGENT");support.attachService(ticket,service);
        tx.executeWithoutResult(status -> {
            var scope=support.ticket(ticket,true).orElseThrow();support.members(service,List.of(author));
            support.change(scope,author);support.append(scope,author,UUID.randomUUID(),"CLAIM",author,null);
        });
        var messages=jdbc.queryForList("SELECT * FROM ticket_messages ORDER BY id");
        var tickets=jdbc.queryForList("SELECT * FROM tickets ORDER BY id");
        var events=jdbc.queryForList("SELECT * FROM ticket_assignment_events ORDER BY id");
        Flyway.configure().dataSource(ds).target("11").load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM ticket_messages ORDER BY id")).isEqualTo(messages);
        assertThat(jdbc.queryForList("SELECT * FROM tickets ORDER BY id")).isEqualTo(tickets);
        assertThat(jdbc.queryForList("SELECT * FROM ticket_assignment_events ORDER BY id")).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT reply_assignment_version IS NULL FROM ticket_messages WHERE id=?",Boolean.class,reply)).isTrue();
        insert(jdbc,ticket,author,UUID.randomUUID(),null,null);
        assertThatThrownBy(() -> insert(jdbc,ticket,null,UUID.randomUUID(),null,null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc,ticket,author,null,null,null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc,ticket,author,UUID.randomUUID(),reply,null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc,ticket,author,UUID.randomUUID(),null,1L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc,ticket,author,replyKey,null,null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_messages",Long.class)).isEqualTo(3);
    }

    private void insert(JdbcTemplate jdbc,long ticket,Long author,UUID request,Long correction,Long version) {
        jdbc.update("""
                INSERT INTO ticket_messages(ticket_id,body,author_username,author_user_id,message_type,
                    client_request_id,corrects_message_id,reply_assignment_version)
                VALUES (?,'followup','synthetic',?,'CUSTOMER_FOLLOWUP',?,?,?)
                """,ticket,author,request,correction,version);
    }
}
