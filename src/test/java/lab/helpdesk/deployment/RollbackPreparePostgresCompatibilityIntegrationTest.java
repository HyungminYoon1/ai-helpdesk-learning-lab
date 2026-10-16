package lab.helpdesk.deployment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lab.helpdesk.HelpdeskApplication;
import lab.helpdesk.ai.processing.AiSuggestionWorkerScheduler;
import lab.helpdesk.ai.processing.AiSuggestionJobWorker;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StandardServletEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual PostgreSQL and production Spring beans; HTTP uses MockMvc, not a browser. */
@Testcontainers
class RollbackPreparePostgresCompatibilityIntegrationTest {
    private static final String USER = "prepare-fixture-user";
    private static final String AGENT = "prepare-fixture-agent";
    private static final String USER_SECRET = UUID.randomUUID().toString();
    private static final String AGENT_SECRET = UUID.randomUUID().toString();
    private static final String APP_DB_USER = "rollback_app";
    private static final String APP_DB_SECRET = UUID.randomUUID().toString();
    private static final String BODY = "원문과 실패 원인을 바꾸지 않는 합성 롤백 검증 문의입니다.";
    private static final List<String> TABLES = List.of("tickets", "ticket_messages", "ai_suggestion_jobs",
            "ai_suggestion_attempts", "ticket_suggestions", "ticket_suggestion_categories");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("rollback_compat")
            .withUsername("postgres").withPassword(UUID.randomUUID().toString())
            .withEnv("HELPDESK_ROLLBACK_APP_PASSWORD", APP_DB_SECRET)
            .withCopyFileToContainer(MountableFile.forClasspathResource("rollback-init/create-app-role.sql"),
                    "/docker-entrypoint-initdb.d/01-prepare-app-role.sql");

    @Test
    void prepare_boots_on_v7_and_v8_reads_new_codes_and_preserves_original_rows() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), APP_DB_USER, APP_DB_SECRET);
        var jdbc = new JdbcTemplate(dataSource);
        // This connection targets only the Testcontainer created by this test class.
        assertThat(dataSource.getUrl().equals(POSTGRES.getJdbcUrl())).isTrue();
        assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole "
                + "AND NOT rolreplication AND NOT rolbypassrls FROM pg_roles WHERE rolname = current_user",
                Boolean.class)).isTrue();

        Receipt success;
        Receipt pending;
        Receipt oldFailure;
        Map<String, String> v7Rows;
        List<Map<String, Object>> originalMigrationHistory;
        Object firstRepository;
        try (var application = startApplication()) {
            requireIsolatedApplication(application);
            firstRepository = application.getBean(lab.helpdesk.ticket.repository.TicketRepository.class);
            assertThat(appliedVersions(jdbc)).containsExactly("1", "2", "3", "4", "5", "6", "7");
            var mvc = mvc(application);
            var userSession = login(mvc, USER, USER_SECRET);
            var agentSession = login(mvc, AGENT, AGENT_SECRET);
            success = receive(mvc, userSession, "기존 제안 보존");
            markTerminal(jdbc, dataSource, success, "SUCCEEDED", null);
            pending = receive(mvc, userSession, "기존 대기 문의 보존");
            oldFailure = receive(mvc, userSession, "기존 실패 사유 보존");
            markTerminal(jdbc, dataSource, oldFailure, "FAILED", "PROVIDER_REFUSED");
            v7Rows = snapshot(jdbc);
            originalMigrationHistory = jdbc.queryForList(
                    "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank");
            for (String code : List.of("PRIVACY_SCAN_TIMEOUT", "PRIVACY_SCAN_INVALID_RESULT")) {
                assertThatThrownBy(() -> jdbc.update(
                        "UPDATE ai_suggestion_jobs SET last_failure_code = ? WHERE id = ?", code, oldFailure.job()))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            verifyRead(mvc, agentSession, success, "SUCCEEDED", null);
            verifyRead(mvc, agentSession, pending, "PENDING", null);
            verifyRead(mvc, agentSession, oldFailure, "FAILED", "PROVIDER_REFUSED");
            verifySecurity(mvc, userSession, pending);
            assertThat(snapshot(jdbc)).isEqualTo(v7Rows);
        }
        assertThat(snapshot(jdbc)).isEqualTo(v7Rows);

        // Simulate only B's schema/data writes. No B app, Provider or real AI is run.
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:rollback-compatibility")
                .target("8").load().migrate();
        assertThat(snapshot(jdbc)).isEqualTo(v7Rows);
        assertThat(jdbc.queryForList("SELECT version, checksum, success FROM flyway_schema_history "
                + "WHERE version <> '8' ORDER BY installed_rank")).isEqualTo(originalMigrationHistory);
        Receipt timeout = fixtureReceipt(jdbc, "합성 검사 시간 초과");
        markTerminal(jdbc, dataSource, timeout, "FAILED", "PRIVACY_SCAN_TIMEOUT");
        Receipt invalid = fixtureReceipt(jdbc, "합성 검사 결과 오류");
        markTerminal(jdbc, dataSource, invalid, "FAILED", "PRIVACY_SCAN_INVALID_RESULT");
        var v8Rows = snapshot(jdbc);
        var v8History = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");

        try (var application = startApplication()) {
            requireIsolatedApplication(application);
            assertThat(application.getBean(lab.helpdesk.ticket.repository.TicketRepository.class))
                    .isNotSameAs(firstRepository);
            assertThat(appliedVersions(jdbc)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
            // Use P's normal startup validation: do not disable Flyway or add an ignore override.
            application.getBean(Flyway.class).validate();
            assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
                    .isEqualTo(v8History);
            var mvc = mvc(application);
            var userSession = login(mvc, USER, USER_SECRET);
            var agentSession = login(mvc, AGENT, AGENT_SECRET);
            verifyRead(mvc, agentSession, success, "SUCCEEDED", null);
            verifyRead(mvc, agentSession, pending, "PENDING", null);
            verifyRead(mvc, agentSession, oldFailure, "FAILED", "PROVIDER_REFUSED");
            verifyRead(mvc, agentSession, timeout, "FAILED", "PRIVACY_SCAN_TIMEOUT");
            verifyRead(mvc, agentSession, invalid, "FAILED", "PRIVACY_SCAN_INVALID_RESULT");
            mvc.perform(get("/api/tickets/{id}/ai-suggestion", pending.ticket()).session(agentSession))
                    .andExpect(status().isOk());
            verifySecurity(mvc, userSession, timeout);
            assertThat(snapshot(jdbc)).isEqualTo(v8Rows);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE status = 'OPEN'", Long.class))
                    .isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_messages WHERE body = ? "
                    + "AND author_username = ?", Long.class, BODY, USER)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts", Long.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions", Long.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestion_categories", Long.class)).isEqualTo(2);
            Receipt added = receive(mvc, userSession, "V8에서 준비 버전의 신규 접수");
            assertThat(added.ticket()).isGreaterThan(invalid.ticket());
            verifyRead(mvc, agentSession, added, "PENDING", null);
            assertThat(jdbc.queryForObject("SELECT current_attempt = 0 AND reserved_generation_count = 0 "
                    + "FROM ai_suggestion_jobs WHERE id = ?", Boolean.class, added.job())).isTrue();
            assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
                    .isEqualTo(v8History);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_messages WHERE body = ? "
                + "AND author_username = ?", Long.class, BODY, USER)).isEqualTo(6);
    }

    private ConfigurableApplicationContext startApplication() {
        var environment = new StandardServletEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var settings = new LinkedHashMap<String, Object>();
        settings.put("spring.config.location", "classpath:/application.properties");
        settings.put("spring.profiles.active", "postgres,deployment");
        settings.put("server.address", "127.0.0.1");
        settings.put("server.port", "0");
        settings.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        settings.put("spring.datasource.username", APP_DB_USER);
        settings.put("spring.datasource.password", APP_DB_SECRET);
        settings.put("spring.datasource.hikari.maximum-pool-size", "2");
        settings.put("spring.datasource.hikari.minimum-idle", "0");
        settings.put("spring.datasource.hikari.connection-timeout", "5000");
        settings.put("spring.flyway.locations", "classpath:db/migration");
        settings.put("helpdesk.deployment.user.username", USER);
        settings.put("helpdesk.deployment.user.password", USER_SECRET);
        settings.put("helpdesk.deployment.agent.username", AGENT);
        settings.put("helpdesk.deployment.agent.password", AGENT_SECRET);
        settings.put("helpdesk.ai.provider.enabled", "false");
        settings.put("helpdesk.ai.worker.enabled", "false");
        environment.getPropertySources().addFirst(new MapPropertySource("isolated-rollback-prepare", settings));
        return new SpringApplicationBuilder(HelpdeskApplication.class).environment(environment)
                .web(WebApplicationType.SERVLET).registerShutdownHook(false).run();
    }

    private void requireIsolatedApplication(ConfigurableApplicationContext application) throws Exception {
        var dataSource = application.getBean(javax.sql.DataSource.class);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL().split("\\?", 2)[0]
                    .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0])).isTrue();
        }
        assertThat(application.getBeansOfType(AiSuggestionProvider.class)).isEmpty();
        assertThat(application.getBeansOfType(AiSuggestionJobWorker.class)).isEmpty();
        assertThat(application.getBeansOfType(AiSuggestionWorkerScheduler.class)).isEmpty();
    }

    private MockMvc mvc(ConfigurableApplicationContext application) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) application)
                .apply(springSecurity()).build();
    }

    private MockHttpSession login(MockMvc mvc, String name, String password) throws Exception {
        MvcResult login = mvc.perform(formLogin().user(name).password(password))
                .andExpect(authenticated().withUsername(name)).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private Receipt receive(MockMvc mvc, MockHttpSession session, String title) throws Exception {
        var mapper = new ObjectMapper();
        MvcResult created = mvc.perform(post("/api/tickets").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("title", title, "body", BODY))))
                .andExpect(status().isCreated()).andReturn();
        long ticket = mapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), APP_DB_USER, APP_DB_SECRET));
        long job = jdbc.queryForObject("SELECT j.id FROM ai_suggestion_jobs j "
                + "JOIN ticket_messages m ON m.id = j.input_message_id WHERE m.ticket_id = ?", Long.class, ticket);
        return new Receipt(ticket, job);
    }

    private Receipt fixtureReceipt(JdbcTemplate jdbc, String title) {
        long ticket = jdbc.queryForObject("INSERT INTO tickets (title, status) VALUES (?, 'OPEN') RETURNING id",
                Long.class, title);
        long message = jdbc.queryForObject("INSERT INTO ticket_messages (ticket_id, body, author_username) "
                + "VALUES (?, ?, ?) RETURNING id", Long.class, ticket, BODY, USER);
        long job = jdbc.queryForObject("INSERT INTO ai_suggestion_jobs (input_message_id, status) "
                + "VALUES (?, 'PENDING') RETURNING id", Long.class, message);
        return new Receipt(ticket, job);
    }

    private void markTerminal(JdbcTemplate jdbc, DriverManagerDataSource source, Receipt receipt,
            String state, String failure) {
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
            jdbc.update("UPDATE ai_suggestion_jobs SET status = ?, current_attempt = 1, reserved_generation_count = 1, "
                    + "first_started_at = clock_timestamp(), processing_deadline_at = clock_timestamp() + INTERVAL '5 minutes', "
                    + "finished_at = clock_timestamp(), last_failure_code = ? WHERE id = ?", state, failure, receipt.job());
            jdbc.update("INSERT INTO ai_suggestion_attempts (job_id, attempt_number, request_kind) VALUES (?, 1, 'INITIAL')",
                    receipt.job());
            if (state.equals("SUCCEEDED")) {
                long suggestion = jdbc.queryForObject("INSERT INTO ticket_suggestions (job_id, summary, priority) "
                        + "VALUES (?, 'Synthetic preserved summary', 'NORMAL') RETURNING id", Long.class, receipt.job());
                jdbc.update("INSERT INTO ticket_suggestion_categories (suggestion_id, category) "
                        + "VALUES (?, 'ACCOUNT'), (?, 'BILLING')", suggestion, suggestion);
            }
        });
    }

    private void verifyRead(MockMvc mvc, MockHttpSession session, Receipt receipt, String state, String failure)
            throws Exception {
        var read = mvc.perform(get("/api/tickets/{id}/ai-suggestion", receipt.ticket()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.job.id").value(receipt.job()))
                .andExpect(jsonPath("$.job.status").value(state));
        if (failure == null) read.andExpect(jsonPath("$.job.failureCode").value(org.hamcrest.Matchers.nullValue()));
        else read.andExpect(jsonPath("$.job.failureCode").value(failure));
        if (state.equals("SUCCEEDED")) {
            read.andExpect(jsonPath("$.suggestion.summary").value("Synthetic preserved summary"))
                    .andExpect(jsonPath("$.suggestion.categories.length()").value(2));
        } else read.andExpect(jsonPath("$.suggestion").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/tickets/{id}", receipt.ticket()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
    }

    private void verifySecurity(MockMvc mvc, MockHttpSession userSession, Receipt receipt) throws Exception {
        mvc.perform(get("/api/tickets/{id}/ai-suggestion", receipt.ticket())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tickets/{id}/ai-suggestion", receipt.ticket()).session(userSession))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets").session(userSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Rejected synthetic\",\"body\":\"No CSRF\"}"))
                .andExpect(status().isForbidden());
    }

    private List<String> appliedVersions(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class);
    }

    private Map<String, String> snapshot(JdbcTemplate jdbc) {
        var snapshot = new LinkedHashMap<String, String>();
        for (String table : TABLES) {
            snapshot.put(table, jdbc.queryForObject("SELECT md5(COALESCE(jsonb_agg(to_jsonb(r) "
                    + "ORDER BY to_jsonb(r)::text)::text, '[]')) FROM " + table + " r", String.class));
        }
        return snapshot;
    }

    private record Receipt(long ticket, long job) { }
}
