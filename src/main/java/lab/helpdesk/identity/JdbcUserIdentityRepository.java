package lab.helpdesk.identity;

import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcUserIdentityRepository implements UserIdentityRepository {
    private final JdbcTemplate jdbc;

    public JdbcUserIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OptionalLong findId(UUID key) {
        if (key == null) return OptionalLong.empty();
        var ids = jdbc.query("SELECT id FROM helpdesk_user_identities WHERE account_key = ?",
                (row, index) -> row.getLong(1), key);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.getFirst());
    }

    @Override
    public long bind(UUID key, String name) {
        if (key == null || name == null || name.isBlank()) {
            throw new IllegalArgumentException("explicit account binding is required");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO helpdesk_user_identities (account_key, login_username) VALUES (?, ?)
                ON CONFLICT (account_key) DO UPDATE SET login_username = EXCLUDED.login_username
                RETURNING id
                """, Long.class, key, name);
        if (id == null) throw new IllegalStateException("identity insert returned no id");
        return id;
    }
}
