package com.silverlakesymmetri.cbs.fileGenerator.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
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

	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
									FilterChain filterChain) throws ServletException, IOException {

		// 1. Resolve path (Handle potential nulls or context paths)
		String requestPath = request.getRequestURI();
		if (request.getContextPath() != null) {
			requestPath = requestPath.substring(request.getContextPath().length());
		}

		// 2. Combined Early Exit (The "Bypass" logic)
		if (!enableValidation || shouldSkipTokenValidation(requestPath)) {
			filterChain.doFilter(request, response);
			return;
		}

		// 3. Extract Token
		String token = request.getHeader(tokenHeaderName);

		// 4. Validate Presence
		if (token == null || token.isEmpty()) {
			logger.warn("Security Alert: Missing token for protected path: {}", requestPath);
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing authentication token");
			return;
		}

		// 5. Validate Authenticity/Expiry
		if (!tokenValidator.validateToken(token)) {
			logger.warn("Security Alert: Invalid/Expired token attempt for path: {}", requestPath);
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
			return;
		}

		// 6. Success - Proceed to Controller
		filterChain.doFilter(request, response);
	}

	private boolean shouldSkipTokenValidation(String requestPath) {
		return pathMatcher.match("/actuator/**", requestPath) ||
				pathMatcher.match("/health/**", requestPath) ||
				pathMatcher.match("/info", requestPath);
	}
}
