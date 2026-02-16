package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.silverlakesymmetri.cbs.fileGenerator.security.TokenAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {
	@Autowired
	private TokenAuthenticationFilter tokenAuthenticationFilter;

	@Override
	public void configure(WebSecurity web) {
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
				.csrf().disable()
				.authorizeRequests()
				.antMatchers("/", "/dashboard").permitAll()
				.antMatchers("/actuator/health").permitAll()
				.antMatchers("/api/v1/**").authenticated()
				.anyRequest().authenticated()
				.and()
				.exceptionHandling()
				.authenticationEntryPoint((req, res, ex) -> {
					res.setContentType("application/json");
					res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
					res.getWriter().write("{\"status\":\"AUTH_ERROR\",\"message\":\"Unauthorized\"}");
				})
				.and()
				.addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.formLogin().disable()
				.httpBasic().disable();

		http.addFilterBefore(
				tokenAuthenticationFilter,
				UsernamePasswordAuthenticationFilter.class
		);

		http.headers().frameOptions().sameOrigin();
	}
}