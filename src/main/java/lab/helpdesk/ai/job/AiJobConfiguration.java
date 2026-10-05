package lab.helpdesk.ai.job;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("postgres")
@EnableConfigurationProperties(AiJobPolicy.class)
public class AiJobConfiguration {
}
