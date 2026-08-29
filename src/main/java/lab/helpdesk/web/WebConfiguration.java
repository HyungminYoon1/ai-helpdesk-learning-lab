package lab.helpdesk.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration
        implements WebMvcConfigurer {

    private final HandlerTimingInterceptor handlerTimingInterceptor;

    public WebConfiguration(
            HandlerTimingInterceptor handlerTimingInterceptor) {

        this.handlerTimingInterceptor = handlerTimingInterceptor;
    }

    @Override
    public void addInterceptors(
            InterceptorRegistry registry) {

        registry.addInterceptor(
                handlerTimingInterceptor);
    }
}
