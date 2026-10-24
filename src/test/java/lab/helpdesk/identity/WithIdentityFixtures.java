package lab.helpdesk.identity;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(IdentityFixtures.Configuration.class)
@TestPropertySource(properties = {
        "helpdesk.support.receipt-service-code=synthetic-support",
        "helpdesk.local.user.account-key=11111111-1111-4111-8111-111111111111",
        "helpdesk.local.agent.account-key=22222222-2222-4222-8222-222222222222",
        "helpdesk.deployment.user.account-key=11111111-1111-4111-8111-111111111111",
        "helpdesk.deployment.agent.account-key=22222222-2222-4222-8222-222222222222"})
public @interface WithIdentityFixtures { }
