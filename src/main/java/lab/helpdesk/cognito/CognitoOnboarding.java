package lab.helpdesk.cognito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.util.WebUtils;

import static lab.helpdesk.cognito.CognitoFailure.Code.*;

final class CognitoOnboarding {
    private static final String ATTRIBUTE = CognitoOnboarding.class.getName();
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final CognitoAccounts accounts;
    private final CognitoSettings settings;
    private final CognitoProvider provider;
    private final CognitoRecoveries recoveries;
    private final Clock clock;

    CognitoOnboarding(CognitoAccounts accounts, CognitoSettings settings, CognitoProvider provider,
            CognitoRecoveries recoveries, Clock clock) {
        this.accounts = accounts;
        this.settings = settings;
        this.provider = provider;
        this.recoveries = recoveries;
        this.clock = clock;
    }

    enum Step {
        START, FIRST_LOGIN, VERIFY_EMAIL, EMAIL_CONFIRMED, LOGOUT, SECOND_LOGIN, ACCEPT,
        RECOVERY_LOGOUT, RECOVERY_LOGIN, RECOVERY_TOTP_ENROLLED, RECOVERY_VERIFIED
    }

    Step step(HttpSession session) {
        if (session == null) return Step.START;
        synchronized (WebUtils.getSessionMutex(session)) {
            Flow flow = read(session);
            return flow == null ? Step.START : flow.step();
        }
    }

    void begin(HttpSession session, String invite) {
        synchronized (WebUtils.getSessionMutex(session)) {
            String hash = invite == null || invite.isBlank() ? null : CognitoAccounts.hashToken(invite.strip());
            save(session, new Flow(Step.FIRST_LOGIN, hash, clock.instant().plus(WINDOW), null, null, null, null));
        }
    }

    void beginRecovery(HttpSession session, UUID recoveryId) {
        synchronized (WebUtils.getSessionMutex(session)) {
            save(session, new Flow(Step.RECOVERY_LOGOUT, null, clock.instant().plus(WINDOW), null, null, null,
                    recoveryId));
        }
    }

    void authorize(HttpSession session, String state) {
        synchronized (WebUtils.getSessionMutex(session)) {
            Flow flow = require(session, Step.FIRST_LOGIN, Step.SECOND_LOGIN, Step.RECOVERY_LOGIN);
            save(session, new Flow(flow.step(), flow.inviteHash(), flow.deadline(),
                    new Attempt(state, clock.instant()), flow.first(), null, flow.recoveryId()));
        }
    }

    Optional<JdbcCognitoAccounts.Account> authenticate(HttpSession session, String state, Proof proof,
            String accessToken) {
        synchronized (WebUtils.getSessionMutex(session)) {
            Flow flow = require(session, Step.FIRST_LOGIN, Step.SECOND_LOGIN, Step.RECOVERY_LOGIN);
            Attempt attempt = flow.attempt();
            Instant now = clock.instant();
            if (attempt == null || state == null || !state.equals(attempt.state())
                    || proof.authenticatedAt() == null || proof.authenticatedAt().isAfter(now.plusSeconds(30))) {
                throw new CognitoFailure(FRESH_AUTHENTICATION_REQUIRED);
            }
            // Consuming the saved request also makes a failed callback require a new authorization request.
            save(session, flow.at(flow.step(), flow.first(), null));
            if (flow.step() == Step.RECOVERY_LOGIN) {
                if (!proof.emailVerified() || !proof.authenticatedAt().isAfter(attempt.startedAt())) {
                    throw new CognitoFailure(FRESH_AUTHENTICATION_REQUIRED);
                }
                boolean verified = recoveries.verifyAuthentication(flow.recoveryId(), proof.identity(),
                        proof.authenticatedAt(), flow.first() == null ? null : flow.first().authenticatedAt());
                save(session, flow.at(verified ? Step.RECOVERY_VERIFIED : Step.RECOVERY_TOTP_ENROLLED, proof, null));
                return Optional.empty();
            }
            if (flow.step() == Step.FIRST_LOGIN) {
                if (flow.inviteHash() == null) {
                    Optional<JdbcCognitoAccounts.Account> linked = accounts.find(proof.identity());
                    if (linked.isPresent()) {
                        var account = linked.get();
                        if (account.status() == AccountStatus.RECOVERY_PENDING) {
                            throw new CognitoFailure(ACCOUNT_RECOVERY_PENDING);
                        }
                        if (account.status() != AccountStatus.ACTIVE) throw new CognitoFailure(ACCOUNT_DISABLED);
                        if (!proof.emailVerified()) throw new CognitoFailure(SIGN_IN_REQUIRED);
                        if (account.reactivatedAt() != null && !proof.authenticatedAt().isAfter(account.reactivatedAt())) {
                            session.removeAttribute(ATTRIBUTE);
                            throw new CognitoFailure(ACCOUNT_RELOGIN_REQUIRED);
                        }
                        provider.requireTotpOnly();
                        session.removeAttribute(ATTRIBUTE);
                        return linked;
                    }
                }
                accounts.checkEnrollment(flow.inviteHash(), proof.identity(), settings.bootstrapSubject(), now);
                if (!proof.emailVerified() && (accessToken == null || accessToken.isBlank())) {
                    throw new CognitoFailure(SIGN_IN_REQUIRED);
                }
                save(session, proof.emailVerified()
                        ? flow.at(Step.EMAIL_CONFIRMED, proof, null)
                        : flow.at(Step.VERIFY_EMAIL, proof, accessToken));
            } else {
                if (!proof.emailVerified() || !proof.identity().equals(flow.first().identity())
                        || !proof.authenticatedAt().isAfter(flow.first().authenticatedAt())
                        || proof.authenticatedAt().getEpochSecond() < attempt.startedAt().getEpochSecond()) {
                    throw new CognitoFailure(FRESH_AUTHENTICATION_REQUIRED);
                }
                provider.requireTotpOnly();
                save(session, flow.at(Step.ACCEPT, proof, null));
            }
            return Optional.empty();
        }
    }

    void requestEmailCode(HttpSession session) {
        synchronized (WebUtils.getSessionMutex(session)) {
            Flow flow = require(session, Step.VERIFY_EMAIL);
            provider.requestEmailCode(flow.accessToken());
        }
    }

    void verifyEmail(HttpSession session, String code) {
        synchronized (WebUtils.getSessionMutex(session)) {
            Flow flow = require(session, Step.VERIFY_EMAIL, Step.EMAIL_CONFIRMED);
            if (flow.step() == Step.EMAIL_CONFIRMED) return;
            if (code == null || !code.matches("[0-9]{6}")) throw new CognitoFailure(INVALID_INPUT);
            provider.verifyEmailCode(flow.accessToken(), code);
            save(session, flow.at(Step.EMAIL_CONFIRMED, flow.first(), null));
        }
    }

    void prepareLogout(HttpSession session) {
        synchronized (WebUtils.getSessionMutex(session)) {
            Flow flow = require(session, Step.EMAIL_CONFIRMED, Step.RECOVERY_TOTP_ENROLLED);
            provider.requireTotpOnly();
            save(session, flow.at(flow.recoveryId() == null ? Step.LOGOUT : Step.RECOVERY_LOGOUT, flow.first(), null));
        }
    }

    void resume(HttpSession session) {
        synchronized (WebUtils.getSessionMutex(session)) {
            Flow flow = require(session, Step.LOGOUT, Step.SECOND_LOGIN, Step.RECOVERY_LOGOUT, Step.RECOVERY_LOGIN);
            save(session, flow.at(flow.recoveryId() == null ? Step.SECOND_LOGIN : Step.RECOVERY_LOGIN, flow.first(), null));
        }
    }

    JdbcCognitoAccounts.Account accept(HttpSession session) {
        synchronized (WebUtils.getSessionMutex(session)) {
            Flow flow = require(session, Step.ACCEPT);
            provider.requireTotpOnly();
            var account = accounts.accept(flow.inviteHash(), flow.first().identity(), settings.bootstrapSubject(),
                    clock.instant());
            session.removeAttribute(ATTRIBUTE);
            return account;
        }
    }

    private Flow require(HttpSession session, Step... steps) {
        Flow flow = read(session);
        if (flow != null) {
            for (Step step : steps) {
                if (flow.step() == step) return flow;
            }
        }
        throw new CognitoFailure(ONBOARDING_STEP_REQUIRED);
    }

    private Flow read(HttpSession session) {
        if (session.getAttribute(ATTRIBUTE) instanceof Flow flow) {
            if (flow.deadline().isAfter(clock.instant())) return flow;
            session.removeAttribute(ATTRIBUTE);
        }
        return null;
    }

    private static void save(HttpSession session, Flow flow) {
        session.setAttribute(ATTRIBUTE, flow);
    }

    record Proof(CognitoAccounts.Identity identity, boolean emailVerified, Instant authenticatedAt) { }
    private record Attempt(String state, Instant startedAt) {
        @Override public String toString() { return "AuthenticationAttempt[redacted]"; }
    }
    private record Flow(Step step, String inviteHash, Instant deadline, Attempt attempt, Proof first, String accessToken,
            UUID recoveryId) {
        Flow at(Step next, Proof proof, String token) {
            return new Flow(next, inviteHash, deadline, null, proof, token, recoveryId);
        }
        @Override public String toString() { return "CognitoFlow[redacted]"; }
    }
}
