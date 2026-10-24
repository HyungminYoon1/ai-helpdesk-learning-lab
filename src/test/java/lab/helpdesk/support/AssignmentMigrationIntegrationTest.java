package lab.helpdesk.support;

import java.util.List;
import java.util.UUID;
import lab.helpdesk.identity.JdbcUserIdentityRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AssignmentMigrationIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v10_preserves_legacy_data_constrains_service_references_and_does_not_regrant_on_restart() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var jdbc=new JdbcTemplate(ds);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        Flyway.configure().dataSource(ds).target("9").load().migrate();
        long legacy=jdbc.queryForObject("INSERT INTO tickets(title,status) VALUES ('legacy','OPEN') RETURNING id",Long.class);
        long message=jdbc.queryForObject("INSERT INTO ticket_messages(ticket_id,body,author_username) VALUES (?,' original ','legacy') RETURNING id",Long.class,legacy);
        var original=jdbc.queryForList("SELECT * FROM ticket_messages WHERE id=?",message);
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(jdbc.queryForObject("SELECT service_id IS NULL AND assignee_user_id IS NULL AND assignment_version=0 FROM tickets WHERE id=?",Boolean.class,legacy)).isTrue();
        assertThat(jdbc.queryForList("SELECT id,ticket_id,body,author_username,created_at,author_user_id,message_type,client_request_id,corrects_message_id FROM ticket_messages WHERE id=?",message)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_service_memberships",Long.class)).isZero();
        var repo=new JdbcSupportRepository(jdbc);
        var identities=new JdbcUserIdentityRepository(jdbc);
        UUID key=UUID.randomUUID();
        long actor=identities.bind(key,"assignment-migration-agent");
        long service=repo.registerService("synthetic-one","Synthetic One");
        long other=repo.registerService("synthetic-two","Synthetic Two");
        repo.registerMember(service,actor,"Agent A","AGENT");
        repo.attachService(legacy,service);
        assertThatThrownBy(() -> jdbc.update("UPDATE tickets SET assignee_user_id=?,assignment_version=1,service_id=? WHERE id=?",actor,other,legacy)).isInstanceOf(DataIntegrityViolationException.class);
        tx.executeWithoutResult(status -> {
            var scope=repo.ticket(legacy,true).orElseThrow();
            assertThat(repo.members(service,List.of(actor))).hasSize(1);
            repo.change(scope,actor);
            repo.append(scope,actor,UUID.randomUUID(),"CLAIM",actor,null);
        });
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ticket_assignment_events(service_id,ticket_id,actor_user_id,client_request_id,operation,expected_version,resulting_version,to_user_id) VALUES (?,?,?,?,'CLAIM',0,1,?)",service,legacy,actor,UUID.randomUUID(),actor)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE ticket_messages SET reply_assignment_version=1 WHERE id=?",message)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repo.queue(service,actor,0,2)).hasSize(1);
        jdbc.update("UPDATE support_service_memberships SET active=false,support_role='SUPERVISOR' WHERE service_id=? AND user_id=?",service,actor);
        var env=new MockEnvironment().withProperty("helpdesk.support.bootstrap.enabled","true")
                .withProperty("helpdesk.support.bootstrap.service-code","synthetic-one")
                .withProperty("helpdesk.support.bootstrap.service-name","Changed")
                .withProperty("helpdesk.support.bootstrap.members[0].account-key",key.toString())
                .withProperty("helpdesk.support.bootstrap.members[0].display-name","Changed")
                .withProperty("helpdesk.support.bootstrap.members[0].role","AGENT");
        tx.executeWithoutResult(status -> new SupportBootstrap(env,repo,identities).run(new DefaultApplicationArguments()));
        tx.executeWithoutResult(status -> {
            var member=repo.members(service,List.of(actor)).getFirst();
            assertThat(member.active()).isFalse();
            assertThat(member.role()).isEqualTo("SUPERVISOR");
            assertThat(member.displayName()).isEqualTo("Agent A");
        });
        assertThat(repo.service(service).orElseThrow().displayName()).isEqualTo("Synthetic One");
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id=?",String.class,message)).isEqualTo(" original ");
    }
}
