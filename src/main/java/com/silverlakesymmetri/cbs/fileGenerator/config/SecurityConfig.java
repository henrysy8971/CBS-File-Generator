package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.silverlakesymmetri.cbs.fileGenerator.security.CorrelationIdFilter;
import com.silverlakesymmetri.cbs.fileGenerator.security.TokenAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

@Configuration
public class SecurityConfig extends WebMvcConfigurerAdapter {
	@Value("${auth.token.header-name}")
	private String tokenHeaderName;

	private final TokenAuthenticationFilter tokenAuthenticationFilter;
	private final CorrelationIdFilter correlationIdFilter;

	@Autowired
	public SecurityConfig(TokenAuthenticationFilter tokenAuthenticationFilter, CorrelationIdFilter correlationIdFilter) {
		this.tokenAuthenticationFilter = tokenAuthenticationFilter;
		this.correlationIdFilter = correlationIdFilter;
	}

	@Bean
	public FilterRegistrationBean correlationFilterRegistration() {
		FilterRegistrationBean registration = new FilterRegistrationBean();
		registration.setFilter(correlationIdFilter);
		registration.addUrlPatterns("/*"); // Apply to ALL URLs, not just /api
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE); // Order -2147483648
		return registration;
	}

	@Bean
	public FilterRegistrationBean securityFilter() {
		FilterRegistrationBean registration = new FilterRegistrationBean();
		registration.setFilter(tokenAuthenticationFilter);
		registration.addUrlPatterns("/api/*");
		// Order 1 ensures it runs early in the filter chain
		registration.setOrder(1);
		return registration;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				// Change "*" to specific internal domains if possible
				.allowedOrigins("*")
				.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
				.allowedHeaders(tokenHeaderName, "Content-Type", "X-User-Name", "X-Request-Id")
				.exposedHeaders("Content-Disposition", "X-Request-Id")
				.allowCredentials(true)
				.maxAge(3600);
	}
}
