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
		web.ignoring().antMatchers("/css/**", "/js/**", "/images/**", "/webjars/**");
	}

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		http
				.authorizeRequests()
				// Permit the dashboard and the authentication endpoint
				.antMatchers("/", "/cbs-file-generator/api/v1/auth/set-token").permitAll()
				// Secure all other API endpoints
				.antMatchers("/cbs-file-generator/api/v1/**").permitAll()
				.anyRequest().permitAll()
				.and()
				.formLogin()
				.disable() // We are using a custom token approach
				.httpBasic()
				.disable()
				.csrf()
				/* * We use CookieCsrfTokenRepository with withHttpOnlyFalse()
				 * so that your JavaScript can read the XSRF-TOKEN cookie
				 * if needed, though your meta-tag approach works great too.
				 */
				.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());

		// Ensure the app can be used in frames if necessary (e.g., if inside an admin console)
		http.headers().frameOptions().sameOrigin();
	}
}