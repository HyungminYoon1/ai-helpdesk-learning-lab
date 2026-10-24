package lab.helpdesk.support;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import static lab.helpdesk.support.SupportException.Code.SUPPORT_NOT_CONFIGURED;

@Component
@Profile("postgres")
public class SupportReceiptRouting {
    private final Environment environment;
    private final SupportRepository repository;
    public SupportReceiptRouting(Environment environment,SupportRepository repository) { this.environment=environment;this.repository=repository; }
    public long serviceId() {
        String code=environment.getProperty("helpdesk.support.receipt-service-code");
        if (code==null || code.isBlank()) throw new SupportException(SUPPORT_NOT_CONFIGURED);
        return repository.service(code).orElseThrow(() -> new SupportException(SUPPORT_NOT_CONFIGURED)).id();
    }
    public void attach(long ticketId,long serviceId) { repository.attachService(ticketId,serviceId); }
}
