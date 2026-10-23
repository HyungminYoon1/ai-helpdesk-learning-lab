package lab.helpdesk.identity;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** The immutable account reference travels with the authenticated Session. */
public final class AccountUserDetails implements UserDetails, org.springframework.security.core.CredentialsContainer {
    private static final long serialVersionUID = 1L;
    private final UserDetails delegate;
    private final UUID accountKey;

    public AccountUserDetails(UserDetails delegate, UUID accountKey) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.accountKey = java.util.Objects.requireNonNull(accountKey);
    }

    public UUID accountKey() { return accountKey; }
    @Override public String getUsername() { return delegate.getUsername(); }
    @Override public String getPassword() { return delegate.getPassword(); }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return delegate.getAuthorities(); }
    @Override public boolean isAccountNonExpired() { return delegate.isAccountNonExpired(); }
    @Override public boolean isAccountNonLocked() { return delegate.isAccountNonLocked(); }
    @Override public boolean isCredentialsNonExpired() { return delegate.isCredentialsNonExpired(); }
    @Override public boolean isEnabled() { return delegate.isEnabled(); }
    @Override public void eraseCredentials() {
        if (delegate instanceof org.springframework.security.core.CredentialsContainer credentials) {
            credentials.eraseCredentials();
        }
    }
    @Override public String toString() { return "AccountUserDetails[redacted]"; }
}
