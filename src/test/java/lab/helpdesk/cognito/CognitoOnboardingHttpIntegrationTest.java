package lab.helpdesk.cognito;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

        private ResultActions withCsrf(MockHttpServletRequestBuilder request) throws Exception {
            var csrf = body(get("/onboarding/state").andExpect(status().isOk()).andReturn());
            return mvc.perform(request.param(csrf.path("csrfName").asText(), csrf.path("csrfToken").asText()));
        }

        void startReauth() throws Exception {
            String location = postForm("/onboarding/reauth").andExpect(status().is3xxRedirection())
                    .andReturn().getResponse().getRedirectedUrl();
            URI logout = URI.create(location);
            assertThat(logout.getHost()).isEqualTo(settings.domain().getHost());
            assertThat(logout.getPath()).isEqualTo("/logout");
            assertThat(parameters(logout.getRawQuery()).get("logout_uri"))
                    .isEqualTo(settings.appOrigin() + "/onboarding/resume");
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
