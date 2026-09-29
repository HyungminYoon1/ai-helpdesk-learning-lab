package lab.helpdesk.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@Profile("local-browser")
public class LocalBrowserCorsConfiguration {

    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(
            Environment environment) {

        String allowedOrigin = getRequiredNonBlank(
                environment,
                "helpdesk.local.cors.allowed-origin");

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.OPTIONS.name()));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.CONTENT_TYPE,
                CSRF_HEADER));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private static String getRequiredNonBlank(
            Environment environment,
            String propertyName) {

        String value = environment.getRequiredProperty(propertyName);

        if (value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " must not be blank");
        }

        return value;
    }
}
