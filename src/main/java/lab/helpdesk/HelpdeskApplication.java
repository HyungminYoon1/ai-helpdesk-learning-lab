package lab.helpdesk;

import lab.helpdesk.cognito.FirstAdministratorRecoveryCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class HelpdeskApplication {

    public static void main(String[] args) {
        if (FirstAdministratorRecoveryCommand.isRequested(args)) {
            int result = FirstAdministratorRecoveryCommand.run(args);
            if (result != 0) System.exit(result);
            return;
        }
        SpringApplication.run(
                HelpdeskApplication.class,
                args);
    }
}
