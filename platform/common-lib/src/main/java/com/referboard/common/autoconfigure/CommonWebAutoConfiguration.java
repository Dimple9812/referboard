package com.referboard.common.autoconfigure;

import com.referboard.common.web.CorrelationIdFilter;
import com.referboard.common.web.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/** Correlation-id filter (runs before Spring Security) and the global exception handler for servlet services. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.security.access.AccessDeniedException",
        "jakarta.validation.ConstraintViolationException"
})
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration {

    /** Registered only for services that use OpenFeign. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    static class FeignForwarding {
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
        public com.referboard.common.web.ForwardingFeignInterceptor forwardingFeignInterceptor() {
            return new com.referboard.common.web.ForwardingFeignInterceptor();
        }
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
