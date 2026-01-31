package com.silverlakesymmetri.cbs.fileGenerator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Date;

@JsonInclude(JsonInclude.Include.NON_NULL) // Don't show null fields in JSON
public class ApiResponse<T> {
	private final boolean success;
	private T data;             // Holds the actual DTO or PagedResponse
	private ApiError error;     // Holds error details if success is false
	private final Date timestamp;

	public ApiResponse(T data) {
		this.success = true;
		this.data = data;
		this.timestamp = new Date();
	}

	public ApiResponse(String code, String message) {
		this.success = false;
		this.error = new ApiError(code, message);
		this.timestamp = new Date();
	}

	// Getters and Setters
	public boolean isSuccess() {
		return success;
	}

	public T getData() {
		return data;
	}

	public ApiError getError() {
		return error;
	}

	public Date getTimestamp() {
		return timestamp;
	}

	public static class ApiError {
		private final String code;
		private final String message;

		public ApiError(String code, String message) {
			this.code = code;
			this.message = message;
		}

		public String getCode() {
			return code;
		}

		public String getMessage() {
			return message;
		}
	}
}
