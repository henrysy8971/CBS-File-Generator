package com.silverlakesymmetri.cbs.fileGenerator.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

	private final TokenValidator tokenValidator;

	public TokenAuthenticationFilter(TokenValidator tokenValidator) {
		this.tokenValidator = tokenValidator;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request,
									HttpServletResponse response,
									FilterChain chain)
			throws ServletException, IOException {

		String token = extractToken(request);

		if (token != null && tokenValidator.validateToken(token)) {
			UsernamePasswordAuthenticationToken auth =
					new UsernamePasswordAuthenticationToken(
							token, null, Collections.emptyList());

			SecurityContextHolder.getContext().setAuthentication(auth);
		}

		chain.doFilter(request, response);
	}

	private String extractToken(HttpServletRequest request) {
		String token = request.getHeader("X-AUTH-TOKEN");

		if (token == null && request.getCookies() != null) {
			for (Cookie c : request.getCookies()) {
				if ("X-AUTH-TOKEN".equals(c.getName())) {
					return c.getValue();
				}
			}
		}
		return token;
	}
}