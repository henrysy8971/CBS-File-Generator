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
				// 1. Public endpoints (No context path prefix needed here!)
				.antMatchers("/", "/dashboard", "/api/v1/auth/set-token").permitAll()
				// 2. Actuator is usually public for monitoring, but can be restricted
				.antMatchers("/actuator/**").permitAll()
				// 3. All other API calls should technically be "authenticated"
				// even if your custom Filter is doing the heavy lifting.
				.antMatchers("/api/v1/**").authenticated()
				.anyRequest().permitAll()
				.and()
				// 4. Disable standard UI logins since you use X-DB-Token
				.formLogin().disable()
				.httpBasic().disable()
				// 5. CSRF Configuration
				.csrf()
				.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
				// Ignore CSRF for API calls if they are strictly stateless/token-based
				// This is common in banking APIs to avoid "403 Forbidden" on POSTs
				.ignoringAntMatchers("/api/v1/**");

		http.headers().frameOptions().sameOrigin();
	}
}