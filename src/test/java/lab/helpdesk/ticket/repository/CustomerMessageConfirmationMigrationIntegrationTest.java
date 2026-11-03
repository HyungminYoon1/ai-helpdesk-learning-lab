package lab.helpdesk.ticket.repository;

import java.util.List;
import java.util.Map;
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
class CustomerMessageConfirmationMigrationIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");

    @Test void v12_preserves_v11_data_and_references_the_same_ticket_assignment_message_and_actor() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        Flyway.configure().dataSource(ds).target("11").load().migrate();
        long actor=new JdbcUserIdentityRepository(jdbc).bind(UUID.randomUUID(),"synthetic-confirmation-actor");
        var support=new JdbcSupportRepository(jdbc);
        long service=support.registerService("confirmation-migration","Synthetic confirmation service");
        support.registerMember(service,actor,"Synthetic actor","AGENT");
        long ticket=jdbc.queryForObject("INSERT INTO tickets(title,status,service_id,requester_user_id) VALUES('legacy','OPEN',?,?) RETURNING id",Long.class,service,actor);
        long other=jdbc.queryForObject("INSERT INTO tickets(title,status,service_id) VALUES('other','OPEN',?) RETURNING id",Long.class,service);
        long inquiry=jdbc.queryForObject("INSERT INTO ticket_messages(ticket_id,body,author_username,author_user_id) VALUES(?,' original ','synthetic',?) RETURNING id",Long.class,ticket,actor);
        long foreignMessage=jdbc.queryForObject("INSERT INTO ticket_messages(ticket_id,body,author_username) VALUES(?,'other','synthetic') RETURNING id",Long.class,other);
        tx.executeWithoutResult(status->{var current=support.ticket(ticket,true).orElseThrow();support.change(current,actor);
            support.append(current,actor,UUID.randomUUID(),"CLAIM",actor,null);});
        var before=snapshot(jdbc);
        Flyway.configure().dataSource(ds).target("12").load().migrate();
        assertThat(snapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_customer_message_confirmations",Long.class)).isZero();
        UUID key=UUID.randomUUID();insert(jdbc,ticket,actor,key,1L,inquiry);
        assertThat(jdbc.queryForObject("SELECT recorded_at IS NOT NULL FROM ticket_customer_message_confirmations",Boolean.class)).isTrue();
        assertThatThrownBy(()->insert(jdbc,ticket,actor,key,1L,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->insert(jdbc,ticket,actor,UUID.randomUUID(),0L,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->insert(jdbc,ticket,actor,UUID.randomUUID(),2L,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->insert(jdbc,ticket,actor,UUID.randomUUID(),1L,foreignMessage)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->insert(jdbc,other,actor,UUID.randomUUID(),1L,foreignMessage)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->insert(jdbc,ticket,null,UUID.randomUUID(),1L,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->insert(jdbc,ticket,Long.MAX_VALUE,UUID.randomUUID(),1L,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->insert(jdbc,ticket,actor,null,1L,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->insert(jdbc,ticket,actor,UUID.randomUUID(),1L,0L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->insert(jdbc,ticket,actor,UUID.randomUUID(),null,inquiry)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_customer_message_confirmations",Long.class)).isOne();
        assertThat(snapshot(jdbc)).isEqualTo(before);
    }

    private void insert(JdbcTemplate jdbc,long ticket,Long actor,UUID request,Long version,Long through){
        jdbc.update("INSERT INTO ticket_customer_message_confirmations(ticket_id,actor_user_id,client_request_id,assignment_version,through_message_id) VALUES(?,?,?,?,?)",
                ticket,actor,request,version,through);
    }
    private Map<String,List<Map<String,Object>>> snapshot(JdbcTemplate jdbc){
        var all=new java.util.LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("tickets","ticket_messages","ticket_assignment_events","helpdesk_user_identities","support_services","support_service_memberships"))
            all.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1,2"));
        return all;
    }
}
