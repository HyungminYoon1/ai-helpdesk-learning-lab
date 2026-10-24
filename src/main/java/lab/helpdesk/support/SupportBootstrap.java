package lab.helpdesk.support;

import java.util.UUID;
import lab.helpdesk.identity.UserIdentityRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("postgres")
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class SupportBootstrap implements ApplicationRunner {
    private final Environment environment;
    private final SupportRepository support;
    private final UserIdentityRepository identities;
    public SupportBootstrap(Environment environment,SupportRepository support,UserIdentityRepository identities) {
        this.environment=environment;this.support=support;this.identities=identities;
    }
    @Override @Transactional
    public void run(ApplicationArguments arguments) {
        String prefix="helpdesk.support.bootstrap.";
        if (!environment.getProperty(prefix+"enabled",Boolean.class,false)) return;
        long service=support.registerService(required(prefix+"service-code"),required(prefix+"service-name"));
        for (int i=0; environment.containsProperty(prefix+"members["+i+"].account-key"); i++) {
            String member=prefix+"members["+i+"].";
            long user;
            try { user=identities.findId(UUID.fromString(required(member+"account-key"))).orElseThrow(); }
            catch (RuntimeException invalid) { throw new IllegalStateException("support bootstrap identity is not bound"); }
            String role=required(member+"role");
            if (!java.util.Set.of("AGENT","SUPERVISOR").contains(role))
                throw new IllegalStateException("support bootstrap role is invalid");
            support.registerMember(service,user,required(member+"display-name"),role);
        }
    }
    private String required(String key) {
        String value=environment.getProperty(key);
        if (value==null || value.isBlank()) throw new IllegalStateException("support bootstrap configuration is incomplete");
        return value;
    }
}
