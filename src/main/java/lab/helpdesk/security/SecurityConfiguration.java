package lab.helpdesk.security;

import lab.helpdesk.cognito.CognitoWebSecurity;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<CognitoWebSecurity.LoginConfigurer> cognitoConfig)
            throws Exception {

        CognitoWebSecurity.LoginConfigurer cognito = cognitoConfig.getIfAvailable();
        RequestMatcher apiRequest =
                PathPatternRequestMatcher.pathPattern("/api/**");
        RequestMatcher managementRequest =
                PathPatternRequestMatcher.pathPattern("/actuator/**");
        RequestMatcher requestMetrics = PathPatternRequestMatcher.pathPattern(
                HttpMethod.GET, "/actuator/metrics/http.server.requests");
        RequestMatcher customerPages = new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern("/tickets.html"),
                PathPatternRequestMatcher.pathPattern("/my-tickets.html"),
                PathPatternRequestMatcher.pathPattern("/conversation.html"));
        RequestMatcher staffPages = new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern("/assignments.html"),
                PathPatternRequestMatcher.pathPattern("/ai-suggestions.html"));
        RequestMatcher ticketCreateRequest =
                PathPatternRequestMatcher.pathPattern(
                        HttpMethod.POST,
                        "/api/tickets");
        RequestMatcher ticketReadRequest =
                PathPatternRequestMatcher.pathPattern(
                        "/api/tickets/{id}");
        // GET Mapping이 HEAD도 처리하므로 일반 인증 규칙으로 우회하지 않게 URI를 보호한다.
        RequestMatcher suggestionReadRequest =
                PathPatternRequestMatcher.pathPattern(
                        "/api/tickets/{id}/ai-suggestion");

        http
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> {
                    authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health").permitAll()
                        .requestMatchers(requestMetrics)
                        .hasRole("AGENT")
                        .requestMatchers("/actuator", "/actuator/**").denyAll()
                        .requestMatchers(ticketCreateRequest)
                        .hasAnyRole("USER", "AGENT")
                        .requestMatchers(ticketReadRequest)
                        .hasRole("AGENT")
                        .requestMatchers(suggestionReadRequest)
                        .hasRole("AGENT")
                        .requestMatchers("/api/tickets/{id}/replies").hasRole("AGENT")
                        .requestMatchers("/api/tickets/{id}/status", "/api/tickets/{id}/status-events",
                                "/api/tickets/{id}/status-changes").hasRole("AGENT")
                        .requestMatchers("/api/tickets/{id}/customer-status", "/api/tickets/{id}/customer-status/reopen")
                                .hasAnyRole("USER", "AGENT")
                        .requestMatchers("/api/tickets/{id}/customer-message-confirmations",
                                "/api/tickets/{id}/customer-message-confirmations/**").hasRole("AGENT")
                        .requestMatchers("/api/services", "/api/services/**").hasRole("AGENT")
                        .requestMatchers("/api/tickets/{id}/assignment", "/api/tickets/{id}/assignment/**",
                                "/api/tickets/{id}/assignment-events").hasRole("AGENT")
                        .requestMatchers("/api/tickets/{id}/messages").hasAnyRole("USER", "AGENT")
                        .requestMatchers("/api/tickets/{id}/customer-messages").hasAnyRole("USER", "AGENT")
                        .requestMatchers("/api/my/tickets").hasAnyRole("USER", "AGENT")
                        .requestMatchers("/api/account-invitations", "/api/accounts", "/api/accounts/**")
                                .hasRole("ACCOUNT_ADMIN");
                    if (cognito == null) {
                        authorize.requestMatchers(apiRequest).authenticated();
                    } else {
                        authorize.requestMatchers(apiRequest).hasRole("USER");
                        authorize.requestMatchers(customerPages).hasRole("USER");
                        authorize.requestMatchers(staffPages).hasRole("AGENT");
                    }
                    authorize.anyRequest().permitAll();
                })
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(
                                        HttpStatus.UNAUTHORIZED),
                                apiRequest)
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                managementRequest));
        if (cognito == null) {
            http.formLogin(Customizer.withDefaults());
        } else {
            RequestMatcher accountPages = new OrRequestMatcher(customerPages, staffPages);
            http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"), accountPages));
            cognito.configure(http, new OrRequestMatcher(apiRequest, accountPages, requestMetrics));
        }

        return http.build();
    }
}
