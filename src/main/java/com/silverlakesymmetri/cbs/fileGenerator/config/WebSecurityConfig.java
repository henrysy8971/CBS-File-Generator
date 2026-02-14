package com.silverlakesymmetri.cbs.fileGenerator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {

	@Override
	public void configure(WebSecurity web) throws Exception {
		// Ignore static resources to improve performance
		web.ignoring().antMatchers(
				"/webjars/**",
				"/css/**",
				"/js/**",
				"/images/**",
				"/favicon.ico"
		);
	}

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		http
				.authorizeRequests()
				// Public Pages
				.antMatchers("/", "/dashboard").permitAll()
				// Monitoring
				.antMatchers("/actuator/**").permitAll()
				// API - Protected by TokenFilter, but we allow it through Spring Security
				// because our custom filter handles the 403 logic.
				.antMatchers("/api/v1/**").permitAll()
				.anyRequest().authenticated()
				.and()
				.formLogin().disable()
				.httpBasic().disable()
				.csrf()
				// Store CSRF token in a cookie readable by JS (XSRF-TOKEN)
				.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());

		http.headers().frameOptions().sameOrigin();
	}
}