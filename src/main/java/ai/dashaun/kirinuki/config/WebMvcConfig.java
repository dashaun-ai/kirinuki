package ai.dashaun.kirinuki.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import ai.dashaun.kirinuki.common.RateLimitingInterceptor;

@Configuration
@ConditionalOnProperty(prefix = "kirinuki.rate-limit", name = "enabled", havingValue = "true")
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitingInterceptor(10, 60_000))
                .addPathPatterns("/videos");
    }
}
