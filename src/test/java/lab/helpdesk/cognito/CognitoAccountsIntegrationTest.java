package lab.helpdesk.cognito;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lab.helpdesk.support.SupportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CognitoAccountsIntegrationTest {
    private static final String ISSUER = "https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_Synthetic";
    private static final String ADMIN_SUBJECT = "test-account-administrator";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CognitoAccounts accounts;
    @Autowired private SupportRepository support;
    private JdbcCognitoAccounts.Account administrator;
    private Instant now;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM helpdesk_account_invitations");
        jdbc.update("DELETE FROM support_service_memberships");
        jdbc.update("DELETE FROM helpdesk_cognito_accounts");
        jdbc.update("DELETE FROM helpdesk_user_identities");
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        administrator = accounts.accept(null, identity(ADMIN_SUBJECT, "owner@example.invalid"), ADMIN_SUBJECT, now);
    }

    @Test
    void concurrentAcceptanceConsumesOneInvitationAndGrantsExactlyOneServiceMembership() throws Exception {
        long service = support.registerService("invite-test", "Invite Test");
        var issued = accounts.issue(administrator.accountKey(), "agent@example.invalid", "AGENT", service,
                "Test Agent", now);
        String hash = CognitoAccounts.hashToken(issued.token());
        var agent = identity("employee-sub", "agent@example.invalid");
        assertThat(issued.expiresAt()).isEqualTo(now.plusSeconds(86_400));
        assertThat(jdbc.queryForObject("SELECT token_hash FROM helpdesk_account_invitations", String.class))
                .isEqualTo(hash).isNotEqualTo(issued.token());
        assertThat(administrator.accountAdmin()).isTrue();
        assertThat(administrator.agent()).isFalse();

        var bothReady = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var callers = List.of(pool.submit(() -> consume(hash, agent, bothReady, go)),
                    pool.submit(() -> consume(hash, agent, bothReady, go)));
            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(callers.get(0).get(10, TimeUnit.SECONDS), callers.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED", "INVITATION_UNAVAILABLE");
        }

        var linked = accounts.find(agent).orElseThrow();
        assertThat(linked.agent()).isTrue();
        assertThat(linked.accountAdmin()).isFalse();
        assertThat(support.services(linked.userId())).extracting(SupportRepository.Service::id).containsExactly(service);
        assertThat(count("SELECT COUNT(*) FROM helpdesk_user_identities")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM helpdesk_account_invitations WHERE accepted_by IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void emailMismatchExpiryAndReissueLeaveTheNewInvitationAvailable() {
        var old = accounts.issue(administrator.accountKey(), "Customer@example.invalid", "CUSTOMER", null, null, now);
        var current = accounts.issue(administrator.accountKey(), "customer@example.invalid", "CUSTOMER", null, null, now);
        var customer = identity("customer-sub", "customer@example.invalid");
        String currentHash = CognitoAccounts.hashToken(current.token());

        assertThatThrownBy(() -> accounts.accept(CognitoAccounts.hashToken(old.token()), customer, null, now))
                .isInstanceOf(CognitoFailure.class).hasMessage("INVITATION_UNAVAILABLE");
        assertThatThrownBy(() -> accounts.accept(currentHash, identity("other", "other@example.invalid"), null, now))
                .isInstanceOf(CognitoFailure.class).hasMessage("INVITATION_UNAVAILABLE");
        assertThatThrownBy(() -> accounts.accept(currentHash, customer, null, current.expiresAt()))
                .isInstanceOf(CognitoFailure.class).hasMessage("INVITATION_UNAVAILABLE");

        var linked = accounts.accept(currentHash, customer, null, now);
        assertThat(linked.accountAdmin()).isFalse();
        assertThat(linked.agent()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM support_service_memberships")).isZero();
    }

    @Test
    void sameSubjectCannotBindASecondAccountOrSilentlyTakeOverTheFirstAdministrator() {
        var joined = accounts.issue(administrator.accountKey(), "first@example.invalid", "CUSTOMER", null, null, now);
        accounts.accept(CognitoAccounts.hashToken(joined.token()), identity("already-linked", "first@example.invalid"),
                null, now);
        var other = accounts.issue(administrator.accountKey(), "second@example.invalid", "CUSTOMER", null, null, now);
        assertThatThrownBy(() -> accounts.accept(CognitoAccounts.hashToken(other.token()),
                identity("already-linked", "second@example.invalid"), null, now))
                .isInstanceOf(CognitoFailure.class).hasMessage("INVITATION_UNAVAILABLE");
        assertThatThrownBy(() -> accounts.accept(null, identity("different-admin", "owner@example.invalid"),
                "different-admin", now)).isInstanceOf(CognitoFailure.class).hasMessage("INVITATION_UNAVAILABLE");
        assertThat(count("SELECT COUNT(*) FROM helpdesk_user_identities")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM helpdesk_account_invitations WHERE accepted_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void databaseCommitRejectionRollsBackBindingMembershipAndInvitationUse() {
        long service = support.registerService("invite-rollback", "Invite Rollback");
        var issued = accounts.issue(administrator.accountKey(), "rollback@example.invalid", "AGENT", service, "Test", now);
        String hash = CognitoAccounts.hashToken(issued.token());
        var employee = identity("deferred-reject", "rollback@example.invalid");
        jdbc.execute("""
                CREATE FUNCTION reject_cognito_invite_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'synthetic deferred check' USING ERRCODE = '23514';
                END;
                $$
                """);
        try {
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER reject_cognito_invite_at_commit
                    AFTER UPDATE ON helpdesk_account_invitations DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW WHEN (NEW.accepted_at IS NOT NULL)
                    EXECUTE FUNCTION reject_cognito_invite_for_test()
                    """);
            assertThatThrownBy(() -> accounts.accept(hash, employee, null, now)).isInstanceOf(RuntimeException.class);
            assertThat(accounts.find(employee)).isEmpty();
            assertThat(count("SELECT COUNT(*) FROM helpdesk_user_identities")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM support_service_memberships")).isZero();
            assertThat(count("SELECT COUNT(*) FROM helpdesk_account_invitations WHERE accepted_at IS NOT NULL")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_cognito_invite_at_commit ON helpdesk_account_invitations");
            jdbc.execute("DROP FUNCTION reject_cognito_invite_for_test()");
        }
        assertThat(accounts.accept(hash, employee, null, now).agent()).isTrue();
    }

    private String consume(String hash, CognitoAccounts.Identity identity, CountDownLatch ready,
            CountDownLatch go) throws InterruptedException {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("acceptance barrier timed out");
        try {
            accounts.accept(hash, identity, null, now);
            return "ACCEPTED";
        } catch (CognitoFailure failure) {
            return failure.code().name();
        }
    }

    private static CognitoAccounts.Identity identity(String subject, String email) {
        return new CognitoAccounts.Identity(ISSUER, subject, email);
    }

    private Long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
