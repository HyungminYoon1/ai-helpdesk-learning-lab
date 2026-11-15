package lab.helpdesk.cognito;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.WebUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

import static lab.helpdesk.cognito.CognitoFailure.Code.SIGN_IN_REQUIRED;

@Configuration(proxyBeanMethods = false)
@Profile("cognito")
public class CognitoWebSecurity {
    @Bean
    CognitoSettings cognitoSettings(Environment environment) {
        return CognitoSettings.read(environment);
    }

    @Bean(destroyMethod = "close")
    CognitoIdentityProviderClient cognitoSdkClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(8))
                        .apiCallAttemptTimeout(Duration.ofSeconds(4)))
                .build();
    }

    @Bean
    CognitoProvider cognitoProvider(CognitoIdentityProviderClient client, CognitoSettings settings) {
        return new AwsCognitoProvider(client, settings);
    }

    @Bean
    CognitoOnboarding cognitoOnboarding(CognitoAccounts accounts, CognitoSettings settings, CognitoProvider provider) {
        return new CognitoOnboarding(accounts, settings, provider, Clock.systemUTC());
    }

    @Bean
    CognitoLogin cognitoLogin(CognitoAccounts accounts) {
        return new CognitoLogin(accounts);
    }

    @Bean
    ClientRegistrationRepository cognitoRegistrations(CognitoSettings settings) {
        boolean confidential = settings.clientSecret() != null && !settings.clientSecret().isBlank();
        var registration = ClientRegistration.withRegistrationId(CognitoSettings.REGISTRATION_ID)
                .clientId(settings.clientId())
                .clientAuthenticationMethod(confidential
                        ? ClientAuthenticationMethod.CLIENT_SECRET_BASIC : ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(settings.appOrigin() + CognitoSettings.CALLBACK_PATH)
                .authorizationUri(settings.domain() + "/oauth2/authorize")
                .tokenUri(settings.domain() + "/oauth2/token")
                .jwkSetUri(settings.issuer() + "/.well-known/jwks.json")
                .issuerUri(settings.issuer())
                .userNameAttributeName("sub")
                .scope("openid", "email", "aws.cognito.signin.user.admin")
                .clientName("Cognito");
        if (confidential) registration.clientSecret(settings.clientSecret());
        return new InMemoryClientRegistrationRepository(registration.build());
    }

    @Bean
    LoginConfigurer cognitoSecurityConfigurer(CognitoSettings settings, CognitoOnboarding onboarding, CognitoLogin session,
            ClientRegistrationRepository registrations) {
        return new LoginConfigurer(settings, onboarding, session, registrations);
    }

    public static final class LoginConfigurer {
        private final CognitoSettings settings;
        private final CognitoOnboarding onboarding;
        private final CognitoLogin login;
        private final ClientRegistrationRepository registrations;
        private final RequestClients clients = new RequestClients();

        LoginConfigurer(CognitoSettings settings, CognitoOnboarding onboarding, CognitoLogin login,
                ClientRegistrationRepository registrations) {
            this.settings = settings;
            this.onboarding = onboarding;
            this.login = login;
            this.registrations = registrations;
        }

        public void configure(HttpSecurity http, RequestMatcher accountRequests) throws Exception {
            var delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
            delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            var resolver = new OAuth2AuthorizationRequestResolver() {
                @Override
                public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                    return bind(request, delegate.resolve(request));
                }

                @Override
                public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
                    return bind(request, delegate.resolve(request, registrationId));
                }

                private OAuth2AuthorizationRequest bind(HttpServletRequest request, OAuth2AuthorizationRequest resolved) {
                    if (resolved == null) return null;
                    var session = request.getSession(false);
                    if (session == null) return null;
                    try {
                        onboarding.authorize(session, resolved.getState());
                        return resolved;
                    } catch (CognitoFailure ex) {
                        return null;
                    }
                }
            };

            http.securityContext(config -> config.securityContextRepository(login.contexts))
                    .csrf(csrf -> csrf.csrfTokenRepository(login.csrf))
                    .requestCache(cache -> cache.disable())
                    .oauth2Login(oauth -> oauth.loginPage("/login")
                            .clientRegistrationRepository(registrations)
                            .authorizedClientRepository(clients)
                            .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                            .successHandler(this::complete)
                            .failureHandler((request, response, failure) ->
                                    redirectError(response, SIGN_IN_REQUIRED)))
                    .logout(logout -> logout.logoutSuccessHandler((request, response, auth) ->
                            response.sendRedirect(logoutUrl("/login"))))
                    .addFilterBefore(login.checkAccount(accountRequests), AuthorizationFilter.class);
        }

        String logoutUrl(String appPath) {
            return UriComponentsBuilder.fromUri(settings.domain()).path("/logout")
                    .queryParam("client_id", settings.clientId())
                    .queryParam("logout_uri", settings.appOrigin() + appPath)
                    .build().encode().toUriString();
        }

        private void complete(HttpServletRequest request, HttpServletResponse response, Authentication auth)
                throws IOException {
            var session = request.getSession(false);
            if (session == null) {
                login.clear(request, response);
                redirectError(response, SIGN_IN_REQUIRED);
                return;
            }

            synchronized (WebUtils.getSessionMutex(session)) {
                login.clear(request, response);
                try {
                    if (!(auth.getPrincipal() instanceof OidcUser user)
                            || !settings.issuer().equals(String.valueOf(user.getIdToken().getIssuer()))
                            || !"id".equals(user.getIdToken().getClaimAsString("token_use"))
                            || user.getSubject() == null || user.getSubject().isBlank() || user.getSubject().length() > 128) {
                        throw new CognitoFailure(SIGN_IN_REQUIRED);
                    }

                    var id = user.getIdToken();
                    var identity = new CognitoAccounts.Identity(settings.issuer(), user.getSubject(),
                            CognitoAccounts.canonicalEmail(id.getEmail()));
                    var proof = new CognitoOnboarding.Proof(identity,
                            Boolean.TRUE.equals(id.getEmailVerified()), id.getAuthenticatedAt());
                    OAuth2AuthorizedClient client = clients.loadAuthorizedClient(
                            CognitoSettings.REGISTRATION_ID, auth, request);
                    var account = onboarding.authenticate(session, request.getParameter("state"), proof,
                            client == null ? null : client.getAccessToken().getTokenValue());
                    if (account.isPresent()) {
                        login.promote(account.get(), request, response);
                        response.sendRedirect("/my-tickets.html");
                    } else {
                        response.sendRedirect("/cognito.html");
                    }
                } catch (CognitoFailure ex) {
                    redirectError(response, ex.code());
                } catch (DataAccessException ex) {
                    redirectError(response, CognitoFailure.Code.COGNITO_UNAVAILABLE);
                } finally {
                    clients.removeAuthorizedClient(CognitoSettings.REGISTRATION_ID, auth, request, response);
                }
            }
        }

        private static void redirectError(HttpServletResponse response, CognitoFailure.Code error) throws IOException {
            response.sendRedirect("/cognito.html?error=" + error.name());
        }
    }

    private static final class RequestClients implements OAuth2AuthorizedClientRepository {
        private static final String ATTRIBUTE = RequestClients.class.getName();

        @SuppressWarnings("unchecked")
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String registrationId, Authentication principal, HttpServletRequest request) {
            if (request.getAttribute(ATTRIBUTE) instanceof OAuth2AuthorizedClient client
                    && registrationId.equals(client.getClientRegistration().getRegistrationId())) return (T) client;
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            request.setAttribute(ATTRIBUTE, client);
        }

        @Override
        public void removeAuthorizedClient(String registrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            request.removeAttribute(ATTRIBUTE);
        }
    }
}
