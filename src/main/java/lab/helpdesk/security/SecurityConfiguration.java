package lab.helpdesk.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http)
            throws Exception {

        RequestMatcher apiRequest =
                PathPatternRequestMatcher.pathPattern("/api/**");
        RequestMatcher ticketCreateRequest =
                PathPatternRequestMatcher.pathPattern(
                        HttpMethod.POST,
                        "/api/tickets");
        RequestMatcher ticketReadRequest =
                PathPatternRequestMatcher.pathPattern(
                        HttpMethod.GET,
                        "/api/tickets/{id}");

        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(ticketCreateRequest)
                        .hasAnyRole("USER", "AGENT")
                        .requestMatchers(ticketReadRequest)
                        .hasRole("AGENT")
                        .requestMatchers(apiRequest).authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(
                                        HttpStatus.UNAUTHORIZED),
                                apiRequest))
                .formLogin(Customizer.withDefaults());

        return http.build();
    }
}
