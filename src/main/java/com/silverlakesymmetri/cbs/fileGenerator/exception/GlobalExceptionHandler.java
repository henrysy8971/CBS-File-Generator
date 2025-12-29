package com.silverlakesymmetri.cbs.fileGenerator.exception;

import com.silverlakesymmetri.cbs.fileGenerator.dto.FileGenerationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<?> handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
		logger.error("Illegal argument exception: {}", ex.getMessage());
		FileGenerationResponse response = new FileGenerationResponse("VALIDATION_ERROR", ex.getMessage());
		return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<?> handleGlobalException(Exception ex, WebRequest request) {
		logger.error("Unexpected error: {}", ex.getMessage(), ex);
		FileGenerationResponse response = new FileGenerationResponse("ERROR", "An unexpected error occurred");
		return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<?> handleRuntimeException(RuntimeException ex, WebRequest request) {
		logger.error("Runtime exception: {}", ex.getMessage(), ex);
		FileGenerationResponse response = new FileGenerationResponse("ERROR", ex.getMessage());
		return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
	}
}
