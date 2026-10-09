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
        RequestMatcher managementRequest =
                PathPatternRequestMatcher.pathPattern("/actuator/**");
        RequestMatcher ticketCreateRequest =
                PathPatternRequestMatcher.pathPattern(
                        HttpMethod.POST,
                        "/api/tickets");
        RequestMatcher ticketReadRequest =
                PathPatternRequestMatcher.pathPattern(
                        HttpMethod.GET,
                        "/api/tickets/{id}");
        // GET Mapping이 HEAD도 처리하므로 일반 인증 규칙으로 우회하지 않게 URI를 보호한다.
        RequestMatcher suggestionReadRequest =
                PathPatternRequestMatcher.pathPattern(
                        "/api/tickets/{id}/ai-suggestion");

        http
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/metrics/http.server.requests")
                        .hasRole("AGENT")
                        .requestMatchers("/actuator", "/actuator/**").denyAll()
                        .requestMatchers(ticketCreateRequest)
                        .hasAnyRole("USER", "AGENT")
                        .requestMatchers(ticketReadRequest)
                        .hasRole("AGENT")
                        .requestMatchers(suggestionReadRequest)
                        .hasRole("AGENT")
                        .requestMatchers(apiRequest).authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(
                                        HttpStatus.UNAUTHORIZED),
                                apiRequest)
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                managementRequest))
                .formLogin(Customizer.withDefaults());

        return http.build();
    }
}
