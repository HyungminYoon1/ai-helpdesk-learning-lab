package lab.helpdesk.identity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;

/** Explicit, synthetic-only provisioning for old AI/receipt experiments; never production code. */
public final class IdentityFixtures {
    private IdentityFixtures() { }

    public static UUID key(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("fixture actor is required");
        return UUID.nameUUIDFromBytes(("isolated-test-account:" + name).getBytes(StandardCharsets.UTF_8));
    }
    public static AuthenticatedActor actor(String name) { return new AuthenticatedActor(key(name), name, name.contains("agent")); }
    public static AccountUserDetails user(UserDetails user) { return new AccountUserDetails(user, key(user.getUsername())); }
    public static long bind(JdbcTemplate jdbc, String name) {
        long id=new JdbcUserIdentityRepository(jdbc).bind(key(name), name);
        var support=new lab.helpdesk.support.JdbcSupportRepository(jdbc);
        long service=support.registerService("synthetic-support","Synthetic support");
        if (name.contains("agent")) support.registerMember(service,id,"Synthetic agent","AGENT");
        return id;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 1)
        ApplicationRunner syntheticIdentityFixture(UserIdentityRepository identities,JdbcTemplate jdbc) {
            return arguments -> {
                for (String name : new String[] {"synthetic-author", "synthetic-live-author", "synthetic-runtime-fixture",
                        "synthetic-receipt-author", "receipt-user-a", "receipt-agent-b", "query-agent", "query-user"}) {
                    bind(jdbc,name);
                }
                var support=new lab.helpdesk.support.JdbcSupportRepository(jdbc);
                identities.findId(UUID.fromString("22222222-2222-4222-8222-222222222222"))
                        .ifPresent(id -> support.registerMember(support.service("synthetic-support").orElseThrow().id(),id,"Synthetic configured agent","AGENT"));
            };
        }
    }
}
