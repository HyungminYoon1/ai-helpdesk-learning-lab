package lab.helpdesk.cognito;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lab.helpdesk.support.SupportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "helpdesk.cognito.issuer=https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_LocalProof",
        "helpdesk.cognito.client-id=syntheticclient",
        "helpdesk.cognito.domain=https://local-proof.auth.ap-northeast-2.amazoncognito.com",
        "helpdesk.cognito.app-origin=http://127.0.0.1:9768",
        "helpdesk.cognito.bootstrap-subject=first-admin-sub",
        "helpdesk.ai.provider.enabled=false",
        "helpdesk.ai.worker.enabled=false"
})
@ActiveProfiles({"postgres", "cognito"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
@Import(CognitoOnboardingHttpIntegrationTest.LocalOidc.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CognitoOnboardingHttpIntegrationTest {
    private static final String ADMIN = "first-admin-sub";
    private static final String EMPLOYEE = "employee-sub";
    private static final String EMAIL = "employee@example.invalid";
    private static final String BOOTSTRAP_EMAIL = "administrator@example.invalid";
    private static final String FIRST_CODE = "927014";
    private static final String TEMPORARY_PASSWORD_BODY = "{\"temporaryPassword\":\"Synthetic-only-42!\"}";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LocalIssuer issuer;
    @Autowired private SupportRepository support;
    @Autowired private CognitoAccounts accounts;
    @Autowired private CognitoSettings settings;
    @MockitoBean private CognitoProvider provider;
    private long service;

    @BeforeEach
    void resetStorage() {
        jdbc.update("DELETE FROM helpdesk_account_recoveries");
        jdbc.update("DELETE FROM helpdesk_account_status_events");
        jdbc.update("DELETE FROM helpdesk_account_invitations");
        jdbc.update("DELETE FROM support_service_memberships");
        jdbc.update("DELETE FROM helpdesk_cognito_accounts");
        jdbc.update("DELETE FROM helpdesk_user_identities");
        service = support.registerService("cognito-http", "Cognito HTTP");
    }

    @Test
    void emailOnlyFirstSessionThenFreshSameIdentityCanAcceptAndGetOnlyItsServiceRole() throws Exception {
        Browser admin = new Browser();
        admin.begin(null);
        admin.login(admin.authorize(), ADMIN, BOOTSTRAP_EMAIL, true, Instant.now().minusSeconds(60))
                .andExpect(redirectedUrl("/cognito.html"));
        admin.get("/api/services").andExpect(status().isUnauthorized());
        admin.startReauth();
        admin.login(admin.authorize(), ADMIN, BOOTSTRAP_EMAIL, true, Instant.now())
                .andExpect(redirectedUrl("/cognito.html"));
        admin.postForm("/onboarding/accept").andExpect(redirectedUrl("/my-tickets.html"));
        admin.get("/api/services").andExpect(status().isForbidden());

        String token = body(admin.postJson("/api/account-invitations", """
                {"email":"employee@example.invalid","role":"AGENT","serviceId":%d,"displayName":"Test Employee"}
                """.formatted(service)).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn()).path("token").asText();

        Browser person = new Browser();
        person.begin(token);
        person.login(person.authorize(), EMPLOYEE, EMAIL, false, Instant.now().minusSeconds(60))
                .andExpect(redirectedUrl("/cognito.html"));
        person.get("/onboarding/state").andExpect(jsonPath("$.step").value("VERIFY_EMAIL"));
        person.get("/api/services").andExpect(status().isUnauthorized());
        person.get("/api/my/tickets").andExpect(status().isUnauthorized());
        person.postJson("/api/account-invitations", "{}").andExpect(status().isUnauthorized());
        person.postForm("/onboarding/accept").andExpect(status().isConflict());
        person.postForm("/onboarding/reauth").andExpect(status().isConflict());
        person.get("/onboarding/resume").andExpect(status().isConflict());
        person.get("/my-tickets.html").andExpect(status().is3xxRedirection());
        assertThat(person.session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
        assertThat(acceptedCount()).isZero();

        person.postForm("/onboarding/email-code").andExpect(status().isNoContent());
        person.postForm("/onboarding/verify-email", "code", FIRST_CODE).andExpect(status().isNoContent());
        person.postForm("/onboarding/verify-email", "code", FIRST_CODE).andExpect(status().isNoContent());
        verify(provider).requestEmailCode(anyString());
        verify(provider).verifyEmailCode(anyString(), anyString());
        person.startReauth();
        person.login(person.authorize(), EMPLOYEE, EMAIL, true, Instant.now())
                .andExpect(redirectedUrl("/cognito.html"));

        person.get("/onboarding/state").andExpect(jsonPath("$.step").value("ACCEPT"));
        person.get("/api/services").andExpect(status().isUnauthorized());
        mvc.perform(post("/onboarding/accept").session(person.session)).andExpect(status().isForbidden());
        assertThat(acceptedCount()).isZero();

        String beforePromotion = person.session.getId();
        person.postForm("/onboarding/accept").andExpect(redirectedUrl("/my-tickets.html"));
        assertThat(person.session.getId()).isNotEqualTo(beforePromotion);
        person.get("/api/services").andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(service));
        person.get("/api/my/tickets").andExpect(status().isOk());
        person.postJson("/api/account-invitations", "{}").andExpect(status().isForbidden());
        person.postForm("/onboarding/accept").andExpect(status().isConflict());
        assertThat(acceptedCount()).isEqualTo(1);

        Browser returning = new Browser();
        returning.begin(null);
        returning.login(returning.authorize(), EMPLOYEE, EMAIL, true, Instant.now())
                .andExpect(redirectedUrl("/my-tickets.html"));
        assertThat(acceptedCount()).isEqualTo(1);
        returning.get("/api/services").andExpect(status().isOk());
        returning.get("/assignments.html").andExpect(status().isOk());
        returning.get("/ai-suggestions.html").andExpect(status().isOk());

        jdbc.update("UPDATE support_service_memberships SET active = false WHERE service_id = ?", service);
        returning.get("/assignments.html").andExpect(status().isForbidden());
        returning.get("/ai-suggestions.html").andExpect(status().isForbidden());
        returning.get("/api/services").andExpect(status().isForbidden());
        returning.get("/my-tickets.html").andExpect(status().isOk());
        returning.get("/api/my/tickets").andExpect(status().isOk());
    }

    @Test
    void disabledAccountsAndOldSessionsStayBlockedUntilAReactivatedAccountSignsInAgain() throws Exception {
        var admin = accounts.accept(null, new CognitoAccounts.Identity(settings.issuer(), ADMIN, BOOTSTRAP_EMAIL),
                ADMIN, Instant.now());
        var invite = accounts.issue(admin.accountKey(), EMAIL, "CUSTOMER", null, null, Instant.now());
        var customer = accounts.accept(CognitoAccounts.hashToken(invite.token()),
                new CognitoAccounts.Identity(settings.issuer(), EMPLOYEE, EMAIL), null, Instant.now());

        Instant previousLogin = Instant.now().minusSeconds(60);
        Browser first = new Browser();
        first.begin(null);
        first.login(first.authorize(), EMPLOYEE, EMAIL, true, previousLogin)
                .andExpect(redirectedUrl("/my-tickets.html"));
        first.get("/api/my/tickets").andExpect(status().isOk());
        Browser second = new Browser();
        second.begin(null);
        second.login(second.authorize(), EMPLOYEE, EMAIL, true, previousLogin)
                .andExpect(redirectedUrl("/my-tickets.html"));
        second.get("/my-tickets.html").andExpect(status().isOk());

        jdbc.update("""
                UPDATE helpdesk_cognito_accounts SET status = 'DISABLED', session_version = 1 WHERE user_id = ?
                """, customer.userId());
        first.get("/api/my/tickets").andExpect(status().isUnauthorized());
        Browser newLogin = new Browser();
        newLogin.begin(null);
        newLogin.login(newLogin.authorize(), EMPLOYEE, EMAIL, true, Instant.now())
                .andExpect(redirectedUrl("/cognito.html?error=ACCOUNT_DISABLED"));
        newLogin.get("/api/my/tickets").andExpect(status().isUnauthorized());

        Instant reactivation = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("""
                UPDATE helpdesk_cognito_accounts
                SET status = 'ACTIVE', session_version = 2, reactivated_at = ? WHERE user_id = ?
                """, reactivation.atOffset(ZoneOffset.UTC), customer.userId());
        // The second session was never used while the account was disabled.
        var redirected = second.get("/my-tickets.html").andExpect(status().is3xxRedirection()).andReturn();
        assertThat(URI.create(redirected.getResponse().getRedirectedUrl()).getPath()).isEqualTo("/login");
        second.get("/api/my/tickets").andExpect(status().isUnauthorized());

        newLogin.begin(null);
        expectCognitoLogout(newLogin.login(newLogin.authorize(), EMPLOYEE, EMAIL, true, reactivation), "/login");
        newLogin.get("/api/my/tickets").andExpect(status().isUnauthorized());

        newLogin.begin(null);
        newLogin.login(newLogin.authorize(), EMPLOYEE, EMAIL, true, Instant.now())
                .andExpect(redirectedUrl("/my-tickets.html"));
        newLogin.get("/api/my/tickets").andExpect(status().isOk());
    }

    @Test
    void freshTokenWithCachedAuthenticationTimeOrDifferentIdentityCannotAccept() throws Exception {
        Browser person = invitedBrowser();
        Instant initialAuth = Instant.now().minusSeconds(60);
        person.login(person.authorize(), EMPLOYEE, EMAIL, true, initialAuth)
                .andExpect(redirectedUrl("/cognito.html"));
        person.startReauth();

        person.login(person.authorize(), EMPLOYEE, EMAIL, true, initialAuth)
                .andExpect(redirectedUrl("/cognito.html?error=FRESH_AUTHENTICATION_REQUIRED"));
        person.login(person.authorize(), "other-user", EMAIL, true, Instant.now())
                .andExpect(redirectedUrl("/cognito.html?error=FRESH_AUTHENTICATION_REQUIRED"));
        person.login(person.authorize(), EMPLOYEE, "changed@example.invalid", true, Instant.now())
                .andExpect(redirectedUrl("/cognito.html?error=FRESH_AUTHENTICATION_REQUIRED"));
        person.login(person.authorize(), EMPLOYEE, EMAIL, false, Instant.now())
                .andExpect(redirectedUrl("/cognito.html?error=FRESH_AUTHENTICATION_REQUIRED"));

        person.postForm("/onboarding/accept").andExpect(status().isConflict());
        person.get("/api/my/tickets").andExpect(status().isUnauthorized());
        assertThat(acceptedCount()).isZero();
    }

    @Test
    void invalidNonceAndAnUnavailableMfaPolicyFailClosed() throws Exception {
        Browser person = invitedBrowser();
        var attempt = person.authorize();
        person.callback(attempt, issuer.issue(attempt, EMPLOYEE, EMAIL, true, Instant.now(), true))
                .andExpect(redirectedUrl("/cognito.html?error=SIGN_IN_REQUIRED"));
        person.postForm("/onboarding/accept").andExpect(status().isConflict());

        person.login(person.authorize(), EMPLOYEE, EMAIL, true, Instant.now().minusSeconds(60))
                .andExpect(redirectedUrl("/cognito.html"));
        person.startReauth();
        doThrow(new CognitoFailure(CognitoFailure.Code.COGNITO_UNAVAILABLE)).when(provider).requireTotpOnly();
        person.login(person.authorize(), EMPLOYEE, EMAIL, true, Instant.now())
                .andExpect(redirectedUrl("/cognito.html?error=COGNITO_UNAVAILABLE"));
        person.postForm("/onboarding/accept").andExpect(status().isConflict());
        assertThat(acceptedCount()).isZero();
    }

    @Test
    void aFailedInvitationCommitLeavesTheBrowserUnauthenticatedUntilRetrySucceeds() throws Exception {
        Browser person = invitedBrowser();
        person.login(person.authorize(), EMPLOYEE, EMAIL, true, Instant.now().minusSeconds(60))
                .andExpect(redirectedUrl("/cognito.html"));
        person.startReauth();
        person.login(person.authorize(), EMPLOYEE, EMAIL, true, Instant.now())
                .andExpect(redirectedUrl("/cognito.html"));

        jdbc.execute("""
                CREATE FUNCTION reject_cognito_confirmation_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic commit rejection' USING ERRCODE = '23514'; END;
                $$
                """);
        try {
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER reject_cognito_confirmation_at_commit
                    AFTER UPDATE ON helpdesk_account_invitations DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW WHEN (NEW.accepted_at IS NOT NULL)
                    EXECUTE FUNCTION reject_cognito_confirmation_for_test()
                    """);
            person.postForm("/onboarding/accept").andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_STORAGE_UNAVAILABLE"));
            person.get("/api/my/tickets").andExpect(status().isUnauthorized());
            assertThat(acceptedCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM helpdesk_cognito_accounts", Long.class)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_cognito_confirmation_at_commit ON helpdesk_account_invitations");
            jdbc.execute("DROP FUNCTION reject_cognito_confirmation_for_test()");
        }

        person.postForm("/onboarding/accept").andExpect(redirectedUrl("/my-tickets.html"));
        person.get("/api/my/tickets").andExpect(status().isOk());
        person.get("/api/services").andExpect(status().isForbidden());
        assertThat(acceptedCount()).isEqualTo(1);
    }

    @Test
    void onlyTheAccountAdministratorCanDisableAndReactivateWithoutRestoringOldSessions() throws Exception {
        var employee = linkedEmployee();
        var administrator = accounts.find(new CognitoAccounts.Identity(settings.issuer(), ADMIN, BOOTSTRAP_EMAIL))
                .orElseThrow();
        String path = accountStatusPath(employee.accountKey());
        Instant oldLogin = Instant.now().minusSeconds(60);
        Browser admin = new Browser();
        admin.signIn(ADMIN, BOOTSTRAP_EMAIL, oldLogin).andExpect(redirectedUrl("/my-tickets.html"));
        Browser first = new Browser();
        first.signIn(EMPLOYEE, EMAIL, oldLogin).andExpect(redirectedUrl("/my-tickets.html"));
        Browser second = new Browser();
        second.signIn(EMPLOYEE, EMAIL, oldLogin).andExpect(redirectedUrl("/my-tickets.html"));
        second.get("/api/services").andExpect(status().isOk());

        admin.get("/api/accounts").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[1].accountKey").value(employee.accountKey().toString()))
                .andExpect(jsonPath("$[1].invitedEmail").value(EMAIL))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].sessionVersion").value(0));
        new Browser().get("/api/accounts").andExpect(status().isUnauthorized());
        first.get("/api/accounts").andExpect(status().isForbidden());
        first.putJson(path, changeStatus("DISABLED", 0, "SECURITY_REVIEW")).andExpect(status().isForbidden());
        mvc.perform(put(path).session(admin.session).contentType(MediaType.APPLICATION_JSON)
                .content(changeStatus("DISABLED", 0, "SECURITY_REVIEW"))).andExpect(status().isForbidden());
        admin.putJson(path, """
                {"status":"DISABLED","reason":"SECURITY_REVIEW"}
                """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        admin.putJson(accountStatusPath(administrator.accountKey()), changeStatus("DISABLED", 0, "SECURITY_REVIEW"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FIRST_ADMINISTRATOR_PROTECTED"));
        admin.putJson(path, changeStatus("DISABLED", 0, "SECURITY_REVIEW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.sessionVersion").value(1));
        admin.putJson(path, changeStatus("DISABLED", 0, "SECURITY_REVIEW"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_STATUS_VERSION_CONFLICT"));
        admin.putJson(path, changeStatus("DISABLED", 1, "SECURITY_REVIEW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionVersion").value(1));
        first.get("/api/my/tickets").andExpect(status().isUnauthorized());
        Browser rejoining = new Browser();
        rejoining.signIn(EMPLOYEE, EMAIL, Instant.now())
                .andExpect(redirectedUrl("/cognito.html?error=ACCOUNT_DISABLED"));

        admin.putJson(path, changeStatus("ACTIVE", 1, "ADMINISTRATIVE_REVIEW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.sessionVersion").value(2));
        second.get("/api/services").andExpect(status().isUnauthorized());
        expectCognitoLogout(rejoining.signIn(EMPLOYEE, EMAIL, oldLogin), "/login");
        rejoining.get("/api/services").andExpect(status().isUnauthorized());
        var reactivatedAt = jdbc.queryForObject(
                "SELECT reactivated_at FROM helpdesk_cognito_accounts WHERE user_id = ?",
                OffsetDateTime.class, employee.userId()).toInstant();
        rejoining.signIn(EMPLOYEE, EMAIL, reactivatedAt.plusSeconds(1))
                .andExpect(redirectedUrl("/my-tickets.html"));
        rejoining.get("/api/services").andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(service));
        assertThat(accounts.find(employee.accountKey()).orElseThrow().userId()).isEqualTo(employee.userId());
        assertThat(jdbc.queryForList("""
                SELECT from_status || ':' || to_status || ':' || reason
                FROM helpdesk_account_status_events
                WHERE target_user_id = ? AND administrator_user_id = ? ORDER BY session_version
                """, String.class, employee.userId(), administrator.userId()))
                .containsExactly("ACTIVE:DISABLED:SECURITY_REVIEW", "DISABLED:ACTIVE:ADMINISTRATIVE_REVIEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM helpdesk_account_status_events", Long.class)).isEqualTo(2);
    }

    @Test
    void rejectedStatusCommitLeavesThePreviousStateAndSessionValid() throws Exception {
        var employee = linkedEmployee();
        Browser admin = new Browser();
        admin.signIn(ADMIN, BOOTSTRAP_EMAIL, Instant.now()).andExpect(redirectedUrl("/my-tickets.html"));
        Browser staff = new Browser();
        staff.signIn(EMPLOYEE, EMAIL, Instant.now()).andExpect(redirectedUrl("/my-tickets.html"));
        String path = accountStatusPath(employee.accountKey());
        String disable = changeStatus("DISABLED", 0, "SECURITY_REVIEW");

        jdbc.execute("""
                CREATE FUNCTION reject_status_change_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic status commit rejection' USING ERRCODE = '23514'; END;
                $$
                """);
        try {
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER reject_status_change_at_commit
                    AFTER INSERT ON helpdesk_account_status_events DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION reject_status_change_for_test()
                    """);
            admin.putJson(path, disable).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_STORAGE_UNAVAILABLE"));
            var account = accounts.find(employee.accountKey()).orElseThrow();
            assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(account.sessionVersion()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM helpdesk_account_status_events", Long.class)).isZero();
            staff.get("/api/services").andExpect(status().isOk());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_status_change_at_commit ON helpdesk_account_status_events");
            jdbc.execute("DROP FUNCTION reject_status_change_for_test()");
        }

        admin.putJson(path, disable).andExpect(status().isOk()).andExpect(jsonPath("$.sessionVersion").value(1));
        staff.get("/api/services").andExpect(status().isUnauthorized());
    }

    @Test
    void recoveryStartCommitsOnceAndBlocksExistingSessionsAndNormalLogin() throws Exception {
        var employee = linkedEmployee();
        var administrator = accounts.find(new CognitoAccounts.Identity(settings.issuer(), ADMIN, BOOTSTRAP_EMAIL))
                .orElseThrow();
        Browser admin = signedInBrowser(ADMIN, BOOTSTRAP_EMAIL);
        Browser staff = signedInBrowser(EMPLOYEE, EMAIL);
        String path = recoveryPath(employee.accountKey());
        UUID requestId = UUID.randomUUID();
        String request = recoveryRequest(requestId, 0, "TOTP_RESET");

        var receipt = body(admin.postJson(path, request).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accountKey").value(employee.accountKey().toString()))
                .andExpect(jsonPath("$.kind").value("TOTP_RESET"))
                .andExpect(jsonPath("$.sessionVersion").value(1))
                .andExpect(jsonPath("$.requestedAt").exists()).andReturn());
        assertThat(UUID.fromString(receipt.path("recoveryId").asText())).isNotNull();
        assertThat(body(admin.postJson(path, request).andExpect(status().isOk()).andReturn())).isEqualTo(receipt);
        admin.postJson(path, recoveryRequest(requestId, 0, "PASSWORD_RESET"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_RECOVERY_REQUEST_CONFLICT"));
        admin.postJson(path, recoveryRequest(UUID.randomUUID(), 1, "TOTP_RESET"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_RECOVERY_PENDING"));

        assertThat(jdbc.queryForMap("""
                SELECT administrator_user_id, client_request_id, previous_status, session_version, kind, reason
                FROM helpdesk_account_recoveries WHERE target_user_id = ?
                """, employee.userId())).containsEntry("administrator_user_id", administrator.userId())
                .containsEntry("client_request_id", requestId).containsEntry("previous_status", "ACTIVE")
                .containsEntry("session_version", 1L).containsEntry("kind", "TOTP_RESET")
                .containsEntry("reason", "SECURITY_REVIEW");
        admin.get("/api/accounts").andExpect(status().isOk())
                .andExpect(jsonPath("$[1].status").value("RECOVERY_PENDING"));
        staff.get("/api/services").andExpect(status().isUnauthorized());
        new Browser().signIn(EMPLOYEE, EMAIL, Instant.now())
                .andExpect(redirectedUrl("/cognito.html?error=ACCOUNT_RECOVERY_PENDING"));
        admin.putJson(accountStatusPath(employee.accountKey()), changeStatus("ACTIVE", 1, "ADMINISTRATIVE_REVIEW"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_RECOVERY_PENDING"));
    }

    @Test
    void recoveryStartRequiresCsrfAndAccountAdminAndPreservesThePriorDisabledState() throws Exception {
        var employee = linkedEmployee();
        var administrator = accounts.find(new CognitoAccounts.Identity(settings.issuer(), ADMIN, BOOTSTRAP_EMAIL))
                .orElseThrow();
        Browser admin = signedInBrowser(ADMIN, BOOTSTRAP_EMAIL);
        Browser staff = signedInBrowser(EMPLOYEE, EMAIL);
        String path = recoveryPath(employee.accountKey());
        String request = recoveryRequest(UUID.randomUUID(), 0, "PASSWORD_RESET");

        staff.postJson(path, request).andExpect(status().isForbidden());
        mvc.perform(post(path).session(admin.session).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        admin.postJson(recoveryPath(administrator.accountKey()), request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRST_ADMINISTRATOR_PROTECTED"));
        admin.putJson(accountStatusPath(employee.accountKey()), changeStatus("RECOVERY_PENDING", 0, "SECURITY_REVIEW"))
                .andExpect(status().isBadRequest());
        admin.putJson(accountStatusPath(employee.accountKey()), changeStatus("DISABLED", 0, "SECURITY_REVIEW"))
                .andExpect(status().isOk());
        admin.postJson(path, request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_STATUS_VERSION_CONFLICT"));
        admin.postJson(path, recoveryRequest(UUID.randomUUID(), 1, "PASSWORD_RESET"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sessionVersion").value(2));

        assertThat(jdbc.queryForObject("""
                SELECT previous_status FROM helpdesk_account_recoveries WHERE target_user_id = ?
                """, String.class, employee.userId())).isEqualTo("DISABLED");
    }

    @Test
    void concurrentRecoveryStartsForOneAccountCommitOnlyOneRequest() throws Exception {
        var employee = linkedEmployee();
        Browser first = signedInBrowser(ADMIN, BOOTSTRAP_EMAIL);
        Browser second = signedInBrowser(ADMIN, BOOTSTRAP_EMAIL);
        String path = recoveryPath(employee.accountKey());
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var password = executor.submit(() -> {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test request not released");
                return first.postJson(path, recoveryRequest(UUID.randomUUID(), 0, "PASSWORD_RESET"))
                        .andReturn().getResponse().getStatus();
            });
            var totp = executor.submit(() -> {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test request not released");
                return second.postJson(path, recoveryRequest(UUID.randomUUID(), 0, "TOTP_RESET"))
                        .andReturn().getResponse().getStatus();
            });
            release.countDown();
            assertThat(List.of(password.get(10, TimeUnit.SECONDS), totp.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM helpdesk_account_recoveries", Long.class)).isEqualTo(1);
        assertThat(accounts.find(employee.accountKey()).orElseThrow().sessionVersion()).isEqualTo(1);
    }

    @Test
    void recoveryCommitFailureRollsBackAccountStateAndAudit() throws Exception {
        var employee = linkedEmployee();
        Browser admin = signedInBrowser(ADMIN, BOOTSTRAP_EMAIL);
        Browser staff = signedInBrowser(EMPLOYEE, EMAIL);
        String path = recoveryPath(employee.accountKey());
        String request = recoveryRequest(UUID.randomUUID(), 0, "TOTP_RESET");

        jdbc.execute("""
                CREATE FUNCTION reject_recovery_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic recovery commit rejection' USING ERRCODE = '23514'; END;
                $$
                """);
        try {
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER reject_recovery_at_commit
                    AFTER INSERT ON helpdesk_account_recoveries DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION reject_recovery_for_test()
                    """);
            admin.postJson(path, request).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_STORAGE_UNAVAILABLE"));
            var account = accounts.find(employee.accountKey()).orElseThrow();
            assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(account.sessionVersion()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM helpdesk_account_recoveries", Long.class)).isZero();
            staff.get("/api/services").andExpect(status().isOk());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_recovery_at_commit ON helpdesk_account_recoveries");
            jdbc.execute("DROP FUNCTION reject_recovery_for_test()");
        }

        admin.postJson(path, request).andExpect(status().isCreated()).andExpect(jsonPath("$.sessionVersion").value(1));
        staff.get("/api/services").andExpect(status().isUnauthorized());
    }

    private JdbcCognitoAccounts.Account linkedEmployee() {
        var admin = accounts.accept(null, new CognitoAccounts.Identity(settings.issuer(), ADMIN, BOOTSTRAP_EMAIL),
                ADMIN, Instant.now());
        var invite = accounts.issue(admin.accountKey(), EMAIL, "AGENT", service, "Test Employee", Instant.now());
        return accounts.accept(CognitoAccounts.hashToken(invite.token()),
                new CognitoAccounts.Identity(settings.issuer(), EMPLOYEE, EMAIL), null, Instant.now());
    }

    @Test
    void passwordRecoveryRequiresFreshProofAndApprovalBeforeASeparateLogin() throws Exception {
        var employee = linkedEmployee();
        Browser admin = signedInBrowser(ADMIN, BOOTSTRAP_EMAIL);
        Browser oldSession = signedInBrowser(EMPLOYEE, EMAIL);
        String path = openRecovery(admin, employee.accountKey(), 0, "PASSWORD_RESET");
        providerUser(true, false, true);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(accounts.find(employee.accountKey()).orElseThrow().status()).isEqualTo(AccountStatus.RECOVERY_PENDING);
            assertThat(jdbc.queryForObject("SELECT provider_mutation_at IS NOT NULL FROM helpdesk_account_recoveries",
                    Boolean.class)).isTrue();
            return null;
        }).when(provider).setTemporaryPassword(eq(EMPLOYEE), anyString());

        admin.postJson(path + "/prepare", "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TEMPORARY_PASSWORD"));
        admin.postJson(path + "/prepare", TEMPORARY_PASSWORD_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("AWAITING_AUTHENTICATION"));
        admin.postJson(path + "/prepare", "{}").andExpect(status().isOk());
        verify(provider, times(1)).setTemporaryPassword(eq(EMPLOYEE), anyString());
        verify(provider).signOutEverywhere(EMPLOYEE);
        verify(provider, never()).removeTotp(anyString());
        admin.postJson(path + "/approve", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RECOVERY_PROOF_REQUIRED"));
        oldSession.get("/api/services").andExpect(status().isUnauthorized());

        Browser recovering = new Browser();
        beginRecoveryAuthentication(recovering, path);
        recovering.login(recovering.authorize(), EMPLOYEE, EMAIL, true, Instant.now().minusSeconds(60))
                .andExpect(redirectedUrl("/cognito.html?error=FRESH_AUTHENTICATION_REQUIRED"));
        recovering.login(recovering.authorize(), ADMIN, BOOTSTRAP_EMAIL, true, Instant.now().plusSeconds(2))
                .andExpect(redirectedUrl("/cognito.html?error=ACCOUNT_RECOVERY_NOT_FOUND"));
        providerUser(false, true, true);
        recovering.login(recovering.authorize(), EMPLOYEE, EMAIL, true, Instant.now().plusSeconds(2))
                .andExpect(redirectedUrl("/cognito.html?error=ACCOUNT_RECOVERY_NOT_READY"));
        providerUser(true, false, true);
        var proofTime = Instant.now().plusSeconds(2);
        recovering.login(recovering.authorize(), EMPLOYEE, EMAIL, true, proofTime)
                .andExpect(redirectedUrl("/cognito.html"));
        recovering.get("/onboarding/state").andExpect(jsonPath("$.step").value("RECOVERY_VERIFIED"));
        recovering.get("/api/services").andExpect(status().isUnauthorized());
        admin.get(path).andExpect(jsonPath("$.phase").value("AUTHENTICATION_VERIFIED"));

        jdbc.execute("""
                CREATE FUNCTION reject_recovery_approval_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic approval commit rejection' USING ERRCODE = '23514'; END;
                $$
                """);
        try {
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER reject_recovery_approval_at_commit
                    AFTER UPDATE ON helpdesk_account_recoveries DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW WHEN (NEW.completed_at IS NOT NULL)
                    EXECUTE FUNCTION reject_recovery_approval_for_test()
                    """);
            admin.postJson(path + "/approve", "{}").andExpect(status().isServiceUnavailable());
            admin.get(path).andExpect(jsonPath("$.phase").value("AUTHENTICATION_VERIFIED"));
            assertThat(accounts.find(employee.accountKey()).orElseThrow().status()).isEqualTo(AccountStatus.RECOVERY_PENDING);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_recovery_approval_at_commit ON helpdesk_account_recoveries");
            jdbc.execute("DROP FUNCTION reject_recovery_approval_for_test()");
        }

        admin.postJson(path + "/approve", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("COMPLETED")).andExpect(jsonPath("$.accountStatus").value("ACTIVE"));
        admin.postJson(path + "/approve", "{}").andExpect(jsonPath("$.sessionVersion").value(2));
        expectCognitoLogout(recovering.signIn(EMPLOYEE, EMAIL, proofTime), "/login");
        recovering.signIn(EMPLOYEE, EMAIL, Instant.now().plusSeconds(5))
                .andExpect(redirectedUrl("/my-tickets.html"));
        recovering.get("/api/services").andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(service));
        assertThat(accounts.find(employee.accountKey()).orElseThrow().userId()).isEqualTo(employee.userId());
    }

    @Test
    void totpRecoveryRechecksAnAmbiguousDeletionAndRequiresLoginAfterEnrollment() throws Exception {
        var employee = linkedEmployee();
        Browser admin = signedInBrowser(ADMIN, BOOTSTRAP_EMAIL);
        String accountStatus = accountStatusPath(employee.accountKey());
        admin.putJson(accountStatus, changeStatus("DISABLED", 0, "SECURITY_REVIEW")).andExpect(status().isOk());
        String path = openRecovery(admin, employee.accountKey(), 1, "TOTP_RESET");
        providerUser(true, false, true);
        doThrow(new CognitoFailure(CognitoFailure.Code.COGNITO_UNAVAILABLE)).when(provider).removeTotp(EMPLOYEE);
        admin.postJson(path + "/prepare", "{}").andExpect(status().isServiceUnavailable());
        admin.get(path).andExpect(jsonPath("$.phase").value("PROVIDER_RESULT_UNCONFIRMED"));
        admin.postJson(path + "/prepare", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RECOVERY_PROVIDER_UNCONFIRMED"));
        admin.postJson(path + "/recheck", "{}").andExpect(status().isConflict());
        providerUser(true, false, false);
        admin.postJson(path + "/recheck", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("AWAITING_AUTHENTICATION"));
        verify(provider).signOutEverywhere(EMPLOYEE);
        verify(provider, times(1)).removeTotp(EMPLOYEE);
        verify(provider, never()).setTemporaryPassword(anyString(), anyString());

        Browser recovering = new Browser();
        beginRecoveryAuthentication(recovering, path);
        recovering.login(recovering.authorize(), EMPLOYEE, EMAIL, true, Instant.now().plusSeconds(2))
                .andExpect(redirectedUrl("/cognito.html?error=ACCOUNT_RECOVERY_NOT_READY"));
        providerUser(true, false, true);
        var enrollmentTime = Instant.now().plusSeconds(2);
        recovering.login(recovering.authorize(), EMPLOYEE, EMAIL, true, enrollmentTime)
                .andExpect(redirectedUrl("/cognito.html"));
        recovering.get("/onboarding/state").andExpect(jsonPath("$.step").value("RECOVERY_TOTP_ENROLLED"));
        admin.postJson(path + "/approve", "{}").andExpect(status().isConflict());
        recovering.startReauth();
        recovering.login(recovering.authorize(), EMPLOYEE, EMAIL, true, enrollmentTime)
                .andExpect(redirectedUrl("/cognito.html?error=FRESH_AUTHENTICATION_REQUIRED"));
        recovering.login(recovering.authorize(), EMPLOYEE, EMAIL, true, Instant.now().plusSeconds(5))
                .andExpect(redirectedUrl("/cognito.html"));
        recovering.get("/api/services").andExpect(status().isUnauthorized());
        admin.postJson(path + "/approve", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("DISABLED")).andExpect(jsonPath("$.sessionVersion").value(3));
        recovering.signIn(EMPLOYEE, EMAIL, Instant.now().plusSeconds(6))
                .andExpect(redirectedUrl("/cognito.html?error=ACCOUNT_DISABLED"));
        admin.putJson(accountStatus, changeStatus("ACTIVE", 3, "ADMINISTRATIVE_REVIEW")).andExpect(status().isOk());
        recovering.signIn(EMPLOYEE, EMAIL, Instant.now().plusSeconds(7)).andExpect(redirectedUrl("/my-tickets.html"));
        recovering.get("/api/services").andExpect(status().isOk());
    }

    @Test
    void rejectedPasswordCanBeRetriedButAnAmbiguousResetIsOnlyRechecked() throws Exception {
        var employee = linkedEmployee();
        Browser admin = signedInBrowser(ADMIN, BOOTSTRAP_EMAIL);
        String path = openRecovery(admin, employee.accountKey(), 0, "PASSWORD_RESET");
        providerUser(true, false, true);
        doThrow(new CognitoFailure(CognitoFailure.Code.INVALID_TEMPORARY_PASSWORD))
                .doThrow(new CognitoFailure(CognitoFailure.Code.COGNITO_UNAVAILABLE))
                .when(provider).setTemporaryPassword(eq(EMPLOYEE), anyString());

        admin.postJson(path + "/prepare", TEMPORARY_PASSWORD_BODY).andExpect(status().isBadRequest());
        admin.get(path).andExpect(jsonPath("$.phase").value("REQUESTED"));
        admin.postJson(path + "/prepare", TEMPORARY_PASSWORD_BODY).andExpect(status().isServiceUnavailable());
        admin.postJson(path + "/prepare", TEMPORARY_PASSWORD_BODY).andExpect(status().isConflict());
        admin.postJson(path + "/recheck", "{}").andExpect(status().isConflict());
        providerUser(false, true, true);
        admin.postJson(path + "/recheck", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("AWAITING_AUTHENTICATION"));
        verify(provider, times(2)).setTemporaryPassword(eq(EMPLOYEE), anyString());
    }

    @Test
    void parallelPreparationDoesNotExecuteTheSameProviderMutationTwice() throws Exception {
        var employee = linkedEmployee();
        Browser first = signedInBrowser(ADMIN, BOOTSTRAP_EMAIL);
        Browser second = signedInBrowser(ADMIN, BOOTSTRAP_EMAIL);
        String path = openRecovery(first, employee.accountKey(), 0, "TOTP_RESET");
        providerUser(true, false, true);
        var inProvider = new CountDownLatch(1);
        var finishProvider = new CountDownLatch(1);
        doAnswer(call -> {
            inProvider.countDown();
            if (!finishProvider.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test mutation not released");
            return null;
        }).when(provider).removeTotp(EMPLOYEE);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var prepare = executor.submit(() -> first.postJson(path + "/prepare", "{}").andReturn().getResponse().getStatus());
            try {
                assertThat(inProvider.await(5, TimeUnit.SECONDS)).isTrue();
                second.postJson(path + "/prepare", "{}").andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("ACCOUNT_RECOVERY_PROVIDER_UNCONFIRMED"));
            } finally {
                finishProvider.countDown();
            }
            assertThat(prepare.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        verify(provider, times(1)).removeTotp(EMPLOYEE);
    }

    private void providerUser(boolean confirmed, boolean passwordChangeRequired, boolean totpRegistered) {
        when(provider.recoveryUser(settings.issuer(), EMPLOYEE)).thenReturn(
                new CognitoProvider.RecoveryUser(EMPLOYEE, confirmed, passwordChangeRequired, totpRegistered));
    }

    private void beginRecoveryAuthentication(Browser browser, String path) throws Exception {
        expectCognitoLogout(browser.postForm("/onboarding/recovery", "recoveryId", path.substring(path.lastIndexOf('/') + 1)),
                "/onboarding/resume");
        browser.get("/onboarding/resume").andExpect(redirectedUrl(CognitoSettings.AUTHORIZE_PATH));
    }

    private String openRecovery(Browser admin, UUID accountKey, long version, String kind) throws Exception {
        String path = recoveryPath(accountKey);
        var receipt = body(admin.postJson(path, recoveryRequest(UUID.randomUUID(), version, kind))
                .andExpect(status().isCreated()).andReturn());
        return path + "/" + receipt.path("recoveryId").asText();
    }

    private static String accountStatusPath(UUID accountKey) {
        return "/api/accounts/" + accountKey + "/status";
    }

    private Browser signedInBrowser(String subject, String email) throws Exception {
        Browser browser = new Browser();
        browser.signIn(subject, email, Instant.now()).andExpect(redirectedUrl("/my-tickets.html"));
        return browser;
    }

    private static String recoveryPath(UUID accountKey) {
        return "/api/accounts/" + accountKey + "/recoveries";
    }

    private static String recoveryRequest(UUID requestId, long version, String kind) {
        return """
                {"clientRequestId":"%s","expectedSessionVersion":%d,"kind":"%s","reason":"SECURITY_REVIEW"}
                """.formatted(requestId, version, kind);
    }

    private static String changeStatus(String status, long version, String reason) {
        return """
                {"status":"%s","expectedSessionVersion":%d,"reason":"%s"}
                """.formatted(status, version, reason);
    }

    private void expectCognitoLogout(ResultActions response, String returnPath) throws Exception {
        String location = response.andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        URI logout = URI.create(location);
        assertThat(logout.getHost()).isEqualTo(settings.domain().getHost());
        assertThat(logout.getPath()).isEqualTo("/logout");
        assertThat(parameters(logout.getRawQuery()).get("logout_uri")).isEqualTo(settings.appOrigin() + returnPath);
    }

    private Browser invitedBrowser() throws Exception {
        var admin = accounts.accept(null, new CognitoAccounts.Identity(settings.issuer(), ADMIN, BOOTSTRAP_EMAIL),
                ADMIN, Instant.now());
        var invite = accounts.issue(admin.accountKey(), EMAIL, "CUSTOMER", null, null, Instant.now());
        Browser browser = new Browser();
        browser.begin(invite.token());
        return browser;
    }

    private Long acceptedCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM helpdesk_account_invitations WHERE accepted_at IS NOT NULL",
                Long.class);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private final class Browser {
        final MockHttpSession session = new MockHttpSession();

        ResultActions get(String path) throws Exception {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .session(session));
        }

        void begin(String invite) throws Exception {
            if (invite == null) {
                postForm("/onboarding/start").andExpect(redirectedUrl(CognitoSettings.AUTHORIZE_PATH));
            } else {
                postForm("/onboarding/start", "invite", invite).andExpect(redirectedUrl(CognitoSettings.AUTHORIZE_PATH));
            }
        }

        ResultActions postForm(String path, String... fields) throws Exception {
            MockHttpServletRequestBuilder request = post(path).session(session);
            for (int i = 0; i < fields.length; i += 2) request.param(fields[i], fields[i+1]);
            return withCsrf(request);
        }

        ResultActions postJson(String path, String body) throws Exception {
            return withCsrf(post(path).session(session).contentType(MediaType.APPLICATION_JSON).content(body));
        }

        ResultActions putJson(String path, String body) throws Exception {
            return withCsrf(put(path).session(session).contentType(MediaType.APPLICATION_JSON).content(body));
        }

        ResultActions signIn(String subject, String email, Instant authenticatedAt) throws Exception {
            begin(null);
            return login(authorize(), subject, email, true, authenticatedAt);
        }

        private ResultActions withCsrf(MockHttpServletRequestBuilder request) throws Exception {
            var csrf = body(get("/onboarding/state").andExpect(status().isOk()).andReturn());
            return mvc.perform(request.param(csrf.path("csrfName").asText(), csrf.path("csrfToken").asText()));
        }

        void startReauth() throws Exception {
            expectCognitoLogout(postForm("/onboarding/reauth"), "/onboarding/resume");
            get("/onboarding/resume").andExpect(redirectedUrl(CognitoSettings.AUTHORIZE_PATH));
        }

        Map<String, String> authorize() throws Exception {
            URI url = URI.create(get(CognitoSettings.AUTHORIZE_PATH).andExpect(status().is3xxRedirection())
                    .andReturn().getResponse().getRedirectedUrl());
            // Only the synthetic issuer is used for token exchange. No request goes to AWS.
            assertThat(url.getHost()).isEqualTo("127.0.0.1");
            var fields = parameters(url.getRawQuery());
            assertThat(fields).containsEntry("code_challenge_method", "S256")
                    .containsKeys("nonce", "state", "code_challenge");
            return fields;
        }

        ResultActions login(Map<String, String> attempt, String subject, String email, boolean verified,
                Instant authenticatedAt) throws Exception {
            return callback(attempt, issuer.issue(attempt, subject, email, verified, authenticatedAt, false));
        }

        ResultActions callback(Map<String, String> attempt, String code) throws Exception {
            return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(CognitoSettings.CALLBACK_PATH).session(session)
                    .param("state", attempt.get("state")).param("code", code));
        }
    }

    private static Map<String, String> parameters(String raw) {
        Map<String, String> values = new HashMap<>();
        for (String item : raw.split("&")) {
            String[] field = item.split("=", 2);
            values.put(URLDecoder.decode(field[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(field[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalOidc {
        @Bean(destroyMethod = "close")
        LocalIssuer localIssuer(CognitoSettings settings) throws Exception {
            return new LocalIssuer(settings);
        }

        @Bean
        @Primary
        ClientRegistrationRepository simulatedRegistration(CognitoSettings settings, LocalIssuer issuer) {
            var registration = ClientRegistration.withRegistrationId("cognito").clientId(settings.clientId())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(settings.appOrigin() + CognitoSettings.CALLBACK_PATH)
                    .authorizationUri(issuer.origin() + "/authorize").tokenUri(issuer.origin() + "/token")
                    .jwkSetUri(issuer.origin() + "/jwks").issuerUri(settings.issuer()).userNameAttributeName("sub")
                    .scope("openid", "email", "aws.cognito.signin.user.admin").clientName("Synthetic Cognito")
                    .build();
            return new InMemoryClientRegistrationRepository(registration);
        }
    }

    static final class LocalIssuer implements AutoCloseable {
        private final CognitoSettings settings;
        private final RSAKey key;
        private final HttpServer server;
        private final Map<String, Grant> grants = new ConcurrentHashMap<>();

        LocalIssuer(CognitoSettings settings) throws Exception {
            this.settings = settings;
            this.key = new RSAKeyGenerator(2048).keyID("synthetic-rsa").generate();
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> send(exchange, 200, new JWKSet(key.toPublicJWK()).toString()));
            server.createContext("/token", this::exchange);
            server.start();
        }

        String origin() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String issue(Map<String, String> authorization, String subject, String email, boolean verified,
                Instant authTime, boolean invalidNonce) throws Exception {
            var claims = new JWTClaimsSet.Builder().issuer(settings.issuer()).subject(subject)
                    .audience(settings.clientId()).issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                    .claim("nonce", invalidNonce ? "wrong-nonce" : authorization.get("nonce"))
                    .claim("token_use", "id").claim("email", email).claim("email_verified", verified)
                    .claim("auth_time", authTime.getEpochSecond()).claim("cognito:groups", List.of("ACCOUNT_ADMIN", "AGENT"))
                    .build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            String code = UUID.randomUUID().toString();
            grants.put(code, new Grant(authorization.get("code_challenge"), jwt.serialize()));
            return code;
        }

        private void exchange(HttpExchange http) throws java.io.IOException {
            try {
                var body = new String(http.getRequestBody().readNBytes(8192), StandardCharsets.UTF_8);
                var data = parameters(body);
                var grant = grants.remove(data.get("code"));
                String redirectUri = settings.appOrigin() + CognitoSettings.CALLBACK_PATH;
                if (grant == null || !"authorization_code".equals(data.get("grant_type"))
                        || !redirectUri.equals(data.get("redirect_uri"))
                        || !settings.clientId().equals(data.get("client_id"))
                        || data.get("code_verifier") == null || !grant.challenge().equals(s256(data.get("code_verifier")))) {
                    send(http, 400, "{\"error\":\"invalid_grant\"}");
                    return;
                }
                send(http, 200, """
                        {"token_type":"Bearer","expires_in":900,"access_token":"synthetic-%s","id_token":"%s"}
                        """.formatted(UUID.randomUUID(), grant.token()));
            } catch (Exception ex) {
                send(http, 400, "{\"error\":\"invalid_grant\"}");
            }
        }

        private static String s256(String value) throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        }

        private static void send(HttpExchange exchange, int status, String json) throws java.io.IOException {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record Grant(String challenge, String token) { }
    }
}
