package lab.helpdesk.cognito;

import java.net.URI;
import java.util.Set;
import org.springframework.core.env.Environment;

record CognitoSettings(String issuer, URI domain, URI appOrigin, String clientId, String clientSecret,
        String bootstrapSubject) {
    static final String REGISTRATION_ID = "cognito";
    static final String AUTHORIZE_PATH = "/oauth2/authorization/" + REGISTRATION_ID;
    static final String CALLBACK_PATH = "/login/oauth2/code/" + REGISTRATION_ID;

    static CognitoSettings read(Environment env) {
        if (!env.matchesProfiles("postgres") || env.matchesProfiles("deployment", "local-browser", "in-memory")) {
            throw new IllegalStateException("COGNITO_REQUIRES_POSTGRES_AND_ITS_OWN_AUTHENTICATION_PROFILE");
        }

        String issuer = setting(env, "issuer");
        if (!issuer.matches("https://cognito-idp\\.ap-northeast-2\\.amazonaws\\.com/ap-northeast-2_[A-Za-z0-9]+")) {
            throw new IllegalStateException("COGNITO_SEOUL_ISSUER_REQUIRED");
        }
        URI domain = origin(setting(env, "domain"), false);
        if (!domain.getHost().matches("[a-z0-9-]+\\.auth\\.ap-northeast-2\\.amazoncognito\\.com")
                || domain.getPort() != -1) {
            throw new IllegalStateException("COGNITO_SEOUL_HOSTED_DOMAIN_REQUIRED");
        }
        URI appOrigin = origin(setting(env, "app-origin"), true);
        if ("https".equals(appOrigin.getScheme())
                && !env.getProperty("server.servlet.session.cookie.secure", Boolean.class, true)) {
            throw new IllegalStateException("COGNITO_REQUIRES_SECURE_HTTPS_SESSION_COOKIE");
        }
        String clientId = setting(env, "client-id");
        if (!clientId.matches("[A-Za-z0-9]{1,128}")) throw new IllegalStateException("COGNITO_CLIENT_ID_INVALID");
        String secret = env.getProperty("helpdesk.cognito.client-secret");
        String admin = env.getProperty("helpdesk.cognito.bootstrap-subject");
        if (admin != null && (admin.length() > 128 || admin.isBlank() || !admin.equals(admin.strip()))) {
            throw new IllegalStateException("COGNITO_BOOTSTRAP_SUBJECT_INVALID");
        }
        return new CognitoSettings(issuer, domain, appOrigin, clientId, secret, admin);
    }

    String poolId() {
        return URI.create(issuer).getPath().substring(1);
    }

    private static String setting(Environment env, String name) {
        String value = env.getProperty("helpdesk.cognito." + name);
        if (value == null || value.isBlank()) throw new IllegalStateException("COGNITO_SETTING_REQUIRED: " + name);
        return value;
    }

    private static URI origin(String value, boolean allowLoopback) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && uri.getRawPath().isEmpty()
                    && ("https".equals(uri.getScheme()) || (allowLoopback
                        && Set.of("localhost", "127.0.0.1", "[::1]").contains(host) && "http".equals(uri.getScheme())))) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // The original configuration value can contain credentials. Keep it out of the error.
        }
        throw new IllegalStateException("COGNITO_ORIGIN_INVALID");
    }

    @Override public String toString() { return "CognitoSettings[redacted]"; }
}
