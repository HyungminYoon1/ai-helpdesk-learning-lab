package lab.helpdesk.ticket.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lab.helpdesk.identity.JdbcUserIdentityRepository;
import lab.helpdesk.support.JdbcSupportRepository;
import lab.helpdesk.ticket.TicketStatus;
import lab.helpdesk.ticket.TicketStatusAction;
import lab.helpdesk.ticket.TicketStatusChange.Scope;
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
class TicketStatusMigrationIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");

    @Test void upgrade_preserves_existing_rows_and_enforces_status_event_integrity_and_atomicity() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var jdbc=new JdbcTemplate(ds);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        Flyway.configure().dataSource(ds).target("12").load().migrate();
        long actor=new JdbcUserIdentityRepository(jdbc).bind(UUID.randomUUID(),"synthetic-status-actor");
        var support=new JdbcSupportRepository(jdbc);
        long service=support.registerService("status-migration","Synthetic status service");
        support.registerMember(service,actor,"Synthetic actor","AGENT");
        long ticket=jdbc.queryForObject("INSERT INTO tickets(title,status,service_id,requester_user_id) VALUES('legacy','IN_PROGRESS',?,?) RETURNING id",Long.class,service,actor);
        long other=jdbc.queryForObject("INSERT INTO tickets(title,status,requester_user_id) VALUES('baseline','RESOLVED',?) RETURNING id",Long.class,actor);
        tx.executeWithoutResult(s->{var old=support.ticket(ticket,true).orElseThrow();support.change(old,actor);
            support.append(old,actor,UUID.randomUUID(),"CLAIM",actor,null);});
        long inquiry=jdbc.queryForObject("INSERT INTO ticket_messages(ticket_id,body,author_username) VALUES(?,' original ','synthetic') RETURNING id",Long.class,ticket);
        long foreign=jdbc.queryForObject("INSERT INTO ticket_messages(ticket_id,body,author_username) VALUES(?,'other','synthetic') RETURNING id",Long.class,other);
        long reply=jdbc.queryForObject("INSERT INTO ticket_messages(ticket_id,body,author_username,author_user_id,message_type,client_request_id,reply_assignment_version) VALUES(?,'reply','synthetic',?,'OFFICIAL_REPLY',?,1) RETURNING id",Long.class,ticket,actor,UUID.randomUUID());
        var before=snapshot(jdbc);
        Flyway.configure().dataSource(ds).target("13").load().migrate();
        assertThat(snapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT status_version FROM tickets",Long.class)).containsOnly(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_status_events",Long.class)).isZero();

        var statuses=new JdbcTicketStatusRepository(jdbc);
        UUID key=UUID.randomUUID();
        var event=tx.execute(s->{assertThat(statuses.lock(ticket)).isTrue();var previous=statuses.snapshot(ticket).orElseThrow();
            statuses.change(previous,TicketStatus.RESOLVED);
            return statuses.append(previous,actor,key,Scope.STAFF,TicketStatusAction.RESOLVE,TicketStatus.RESOLVED,1L,"resolved",reply,inquiry);});
        assertThat(event.statusVersion()).isEqualTo(1);
        assertThat(event.assignmentVersion()).isEqualTo(1);
        assertThat(event.recordedAt()).isNotNull();
        assertThat(statuses.submission(ticket,actor,key)).contains(event);
        assertThat(statuses.snapshot(ticket).orElseThrow().status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(statuses.events(ticket,0,2)).containsExactly(event);

        // Database constraints, independently of the Service's semantic checks.
        String insert="INSERT INTO ticket_status_events(ticket_id,actor_user_id,client_request_id,request_scope,operation,expected_status_version,resulting_status_version,expected_assignment_version,assignment_version,from_status,to_status,reason,related_reply_message_id,through_customer_message_id) VALUES(?,?,?,'STAFF','RESOLVE',1,2,1,1,'IN_PROGRESS','RESOLVED','reason',?,?)";
        assertThatThrownBy(()->jdbc.update(insert,ticket,actor,key,reply,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update(insert,ticket,actor,UUID.randomUUID(),foreign,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update(insert,ticket,Long.MAX_VALUE,UUID.randomUUID(),reply,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update(insert,other,actor,UUID.randomUUID(),reply,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE ticket_status_events SET resulting_status_version=3 WHERE id=?",event.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE ticket_status_events SET assignment_version=2,expected_assignment_version=2 WHERE id=?",event.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE ticket_status_events SET request_scope='REQUESTER',expected_assignment_version=NULL WHERE id=?",event.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE ticket_status_events SET reason=' ' WHERE id=?",event.id())).isInstanceOf(DataIntegrityViolationException.class);

        // Baseline assignment zero has no invented assignment event.
        tx.executeWithoutResult(s->{assertThat(statuses.lock(other)).isTrue();var old=statuses.snapshot(other).orElseThrow();
            statuses.change(old,TicketStatus.OPEN);
            statuses.append(old,actor,UUID.randomUUID(),Scope.REQUESTER,TicketStatusAction.REOPEN,TicketStatus.OPEN,null,"reopen",null,null);});
        assertThat(statuses.snapshot(other).orElseThrow().scope().version()).isZero();
        assertThat(statuses.snapshot(other).orElseThrow().statusVersion()).isEqualTo(1);

        var prior=statuses.snapshot(other).orElseThrow();
        assertThatThrownBy(()->tx.executeWithoutResult(s->{statuses.lock(other);
            statuses.change(prior,TicketStatus.IN_PROGRESS);
            statuses.append(prior,Long.MAX_VALUE,UUID.randomUUID(),Scope.STAFF,TicketStatusAction.START,TicketStatus.IN_PROGRESS,1L,"failure",null,null);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(statuses.snapshot(other)).contains(prior);
        assertThat(statuses.events(other,0,10)).hasSize(1);
    }
    private Map<String,List<Map<String,Object>>> snapshot(JdbcTemplate jdbc) {
        var result=new java.util.LinkedHashMap<String,List<Map<String,Object>>>();
        result.put("tickets",jdbc.queryForList("SELECT id,title,status,requester_user_id,service_id,assignee_user_id,assignment_version FROM tickets ORDER BY id"));
        for(String table:List.of("ticket_messages","ticket_assignment_events","ticket_customer_message_confirmations"))
            result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        return result;
    }
}
