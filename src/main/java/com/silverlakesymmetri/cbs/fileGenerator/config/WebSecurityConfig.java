package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.silverlakesymmetri.cbs.fileGenerator.security.TokenAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {
	@Autowired
	private TokenAuthenticationFilter tokenAuthenticationFilter;

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
			.csrf()
				.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
			.and()
			.authorizeRequests()
				.antMatchers("/", "/dashboard").permitAll()
				.antMatchers("/actuator/health").permitAll()
				.antMatchers("/actuator/**").authenticated()
				.antMatchers("/api/v1/admin/**").authenticated()
				.antMatchers("/api/v1/**").authenticated()
				.anyRequest().authenticated()
			.and()
			.formLogin().disable()
			.httpBasic().disable()
			.sessionManagement()
				.sessionCreationPolicy(SessionCreationPolicy.STATELESS);

		http.addFilterBefore(
			tokenAuthenticationFilter,
			UsernamePasswordAuthenticationFilter.class
		);

		http.headers().frameOptions().sameOrigin();
	}
}