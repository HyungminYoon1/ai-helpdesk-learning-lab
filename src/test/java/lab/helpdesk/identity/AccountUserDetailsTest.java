package lab.helpdesk.identity;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.userdetails.User;
import static org.assertj.core.api.Assertions.*;

class AccountUserDetailsTest {
    @Test
    void session_actor_retains_key_and_credential_erasure_does_not_break_next_login() {
        UUID key = UUID.randomUUID();
        var configured = new AccountUserDetails(User.withUsername("synthetic-agent")
                .password("synthetic-encoded-fixture").roles("USER", "AGENT").build(), key);
        var users = new IdentityAwareInMemoryUserDetailsManager(configured);
        var first = users.loadUserByUsername("synthetic-agent");
        var authentication = UsernamePasswordAuthenticationToken.authenticated(first, null, first.getAuthorities());
        var actor = AuthenticatedActorFactory.from(authentication);
        assertThat(actor.accountKey()).isEqualTo(key);
        assertThat(actor.agent()).isTrue();
        ((CredentialsContainer) first).eraseCredentials();
        assertThat(first.getPassword()).isNull();
        assertThat(users.loadUserByUsername("synthetic-agent").getPassword()).isNotNull();
        assertThat(AuthenticatedActorFactory.from(authentication).accountKey()).isEqualTo(key);
        assertThat(first.toString()).doesNotContain("synthetic-agent", "fixture");
    }

    @Test
    void distinct_configured_accounts_cannot_share_an_identity_key() {
        UUID key = UUID.randomUUID();
        var first = new AccountUserDetails(User.withUsername("synthetic-a").password("fixture").roles("USER").build(), key);
        var second = new AccountUserDetails(User.withUsername("synthetic-b").password("fixture").roles("USER").build(), key);
        assertThatThrownBy(() -> new IdentityAwareInMemoryUserDetailsManager(first, second))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("ACCOUNT_IDENTITY_KEY_REUSED");
    }

    @Test
    void plain_or_missing_principal_is_not_promoted_to_a_durable_identity() {
        var plain = User.withUsername("synthetic-agent").password("fixture").roles("AGENT").build();
        var auth = UsernamePasswordAuthenticationToken.authenticated(plain, null, plain.getAuthorities());
        assertThatThrownBy(() -> AuthenticatedActorFactory.from(auth)).isInstanceOf(IdentityNotLinkedException.class);
        assertThatThrownBy(() -> AuthenticatedActorFactory.from(null)).isInstanceOf(IdentityNotLinkedException.class);
    }
}
