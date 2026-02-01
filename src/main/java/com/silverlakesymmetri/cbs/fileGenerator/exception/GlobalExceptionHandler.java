package com.silverlakesymmetri.cbs.fileGenerator.exception;

import com.silverlakesymmetri.cbs.fileGenerator.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	// 400 - Validation/Input Errors
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
		logger.error("Illegal argument exception: {}", ex.getMessage(), ex);
		return ResponseEntity.badRequest().body(new ApiResponse<>("VALIDATION_ERROR", ex.getMessage()));
	}

	// 404 - Resource Missing
	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex) {
		logger.error("Not Found: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiResponse<>("NOT_FOUND", ex.getMessage()));
	}

	// 500 - Application Configuration Issues
	@ExceptionHandler(ConfigurationException.class)
	public ResponseEntity<ApiResponse<Void>> handleConfigException(ConfigurationException ex) {
		logger.error("Configuration Error: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiResponse<>("CONFIG_ERROR", ex.getMessage()));
	}

	// 403 - Security / Access Violations
	@ExceptionHandler(ForbiddenException.class)
	public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException ex) {
		logger.error("Security/Access Violation: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiResponse<>("FORBIDDEN", ex.getMessage()));
	}

	// 410 - File was deleted by Maintenance Tasklet
	@ExceptionHandler(GoneException.class)
	public ResponseEntity<ApiResponse<Void>> handleGone(GoneException ex) {
		logger.info("Resource Gone: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.GONE).body(new ApiResponse<>("GONE", ex.getMessage()));
	}

	// 500 - Catch-all for Security (Obfuscated for safety)
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
		logger.error("CRITICAL UNHANDLED ERROR: {}", ex.getMessage(), ex);
		ApiResponse<Void> response = new ApiResponse<>("INTERNAL_ERROR",
				"An unexpected system error occurred. Please contact support.");
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
	}

	// 409 - State Conflicts (Job already running)
	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
		logger.warn("Conflict: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>("CONFLICT", ex.getMessage()));
	}

	// 422 - Business Rule Violation (Invalid Status Transition)
	@ExceptionHandler(LifecycleException.class)
	public ResponseEntity<ApiResponse<Void>> handleIllegalState(LifecycleException ex) {
		logger.warn("Lifecycle Violation: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ApiResponse<>("LIFECYCLE_ERROR", ex.getMessage()));
	}
}
