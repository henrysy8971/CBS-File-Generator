package com.silverlakesymmetri.cbs.fileGenerator.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger logger = LoggerFactory.getLogger(TokenAuthenticationFilter.class);

	@Autowired
	private TokenValidator tokenValidator;

	@Value("${auth.token.header-name:X-DB-Token}")
	private String tokenHeaderName;

	@Value("${auth.token.enable-validation:true}")
	private boolean enableValidation;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
									FilterChain filterChain) throws ServletException, IOException {

		// Skip validation for health check endpoints
		String requestPath = request.getRequestURI();
		if (shouldSkipTokenValidation(requestPath)) {
			filterChain.doFilter(request, response);
			return;
		}

		if (!enableValidation) {
			filterChain.doFilter(request, response);
			return;
		}

		String token = request.getHeader(tokenHeaderName);

		if (token == null || token.isEmpty()) {
			logger.warn("Missing authentication token for request: {}", requestPath);
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing authentication token");
			return;
		}

		if (!tokenValidator.validateToken(token)) {
			logger.warn("Invalid or expired token for request: {}", requestPath);
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
			return;
		}

		filterChain.doFilter(request, response);
	}

	private boolean shouldSkipTokenValidation(String requestPath) {
		return requestPath.contains("/health") ||
				requestPath.contains("/info") ||
				requestPath.contains("/actuator");
	}
}
