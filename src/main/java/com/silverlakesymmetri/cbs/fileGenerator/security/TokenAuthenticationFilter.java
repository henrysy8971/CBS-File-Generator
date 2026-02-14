package com.silverlakesymmetri.cbs.fileGenerator.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silverlakesymmetri.cbs.fileGenerator.dto.FileGenerationResponse;
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
	private final TokenValidator tokenValidator;
	private final ObjectMapper objectMapper;

	@Value("${auth.token.header-name}")
	private String tokenHeaderName;

	@Value("${auth.token.enable-validation:true}")
	private boolean enableValidation;

	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	@Autowired
	public TokenAuthenticationFilter(TokenValidator tokenValidator, ObjectMapper objectMapper) {
		this.tokenValidator = tokenValidator;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request,
									HttpServletResponse response,
									FilterChain filterChain
	) throws ServletException, IOException {

		String requestPath = request.getServletPath();

		if (!enableValidation) {
			filterChain.doFilter(request, response);
			return;
		}

		String token = request.getHeader(tokenHeaderName);

		// 1. Check Presence
		if (token == null || token.isEmpty()) {
			if (request.getCookies() != null) {
				for (javax.servlet.http.Cookie cookie : request.getCookies()) {
					if (tokenHeaderName.equals(cookie.getName())) {
						token = cookie.getValue();
						break;
					}
				}
			}
		}

		if (token == null || token.isEmpty()) {
			logger.warn("Security Alert: Missing token for protected path: {}", requestPath);
			writeErrorResponse(response, "Missing authentication token");
			return;
		}

		// 2. Validate Authenticity
		if (!tokenValidator.validateToken(token)) {
			logger.warn("Security Alert: Invalid/Expired token attempt for path: {}", requestPath);
			writeErrorResponse(response, "Invalid or expired token");
			return;
		}

		filterChain.doFilter(request, response);
	}

	/**
	 * Helper method to write a standardized JSON error response.
	 * This replaces the generic HTML error page.
	 */
	private void writeErrorResponse(HttpServletResponse response, String message) throws IOException {
		FileGenerationResponse errorResponse = new FileGenerationResponse("AUTH_ERROR", message);

		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");

		// Convert the DTO to a JSON string and write it to the response body
		String json = objectMapper.writeValueAsString(errorResponse);
		response.getWriter().write(json);
	}
}
