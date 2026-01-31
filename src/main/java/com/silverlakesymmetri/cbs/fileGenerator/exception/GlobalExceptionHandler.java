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

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
			IllegalArgumentException ex) {
		logger.error("Illegal argument exception: {}", ex.getMessage(), ex);
		ApiResponse<Void> response = new ApiResponse<>("VALIDATION_ERROR", ex.getMessage());
		return ResponseEntity.badRequest().body(response);
	}

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNotFound(
			NotFoundException ex) {
		logger.error("Not found exception: {}", ex.getMessage(), ex);
		ApiResponse<Void> response = new ApiResponse<>("NOT_FOUND", ex.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	@ExceptionHandler(ConfigurationException.class)
	public ResponseEntity<ApiResponse<Void>> handleConfigException(
			ConfigurationException ex) {
		logger.error("Configuration exception: {}", ex.getMessage(), ex);
		ApiResponse<Void> response = new ApiResponse<>("NOT_FOUND", ex.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
	}

	@ExceptionHandler(ForbiddenException.class)
	public ResponseEntity<ApiResponse<Void>> handleForbidden(
			ForbiddenException ex) {
		logger.error("Forbidden exception: {}", ex.getMessage(), ex);
		ApiResponse<Void> response = new ApiResponse<>("FORBIDDEN", ex.getMessage());
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
	}

	@ExceptionHandler(GoneException.class)
	public ResponseEntity<ApiResponse<Void>> handleGone(
			GoneException ex) {
		logger.error("Gone exception: {}", ex.getMessage(), ex);
		ApiResponse<Void> response = new ApiResponse<>("GONE", ex.getMessage());
		return ResponseEntity.status(HttpStatus.GONE).body(response);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(
			Exception ex) {
		logger.error("Exception: {}", ex.getMessage(), ex);
		ApiResponse<Void> response = new ApiResponse<>("INTERNAL_ERROR", ex.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
	}

	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
		logger.error("Runtime exception: {}", ex.getMessage(), ex);
		ApiResponse<Void> response = new ApiResponse<>("ERROR", ex.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
	}
}
