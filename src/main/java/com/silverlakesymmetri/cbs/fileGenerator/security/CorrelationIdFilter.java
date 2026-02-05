package com.silverlakesymmetri.cbs.fileGenerator.security;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {
	public static final String MDC_KEY = "requestId";
	public static final String HEADER_NAME = "X-Request-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
									FilterChain filterChain) throws ServletException, IOException {
		// 1. Extract or Generate ID
		String requestId = request.getHeader(HEADER_NAME);
		if (requestId == null || requestId.isEmpty()) {
			requestId = UUID.randomUUID().toString();
		}

		// 2. Put into MDC for logging
		MDC.put(MDC_KEY, requestId);

		// 3. Put into response so client knows the trace ID
		response.setHeader(HEADER_NAME, requestId);

		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY); // Clean up
		}
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}
}