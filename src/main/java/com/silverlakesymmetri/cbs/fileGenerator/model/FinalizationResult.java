package com.silverlakesymmetri.cbs.fileGenerator.model;

public enum FinalizationResult {

	SUCCESS(true, false),

	INVALID_PART_FILE(false, false),
	SHA_GENERATION_FAILED(false, true),
	MOVE_FAILED(false, true),
	PERMISSION_FAILED(false, false),
	SECURITY_ERROR(false, false),
	IO_ERROR(false, true);

	private final boolean success;
	private final boolean retryable;

	FinalizationResult(boolean success, boolean retryable) {
		this.success = success;
		this.retryable = retryable;
	}

	public boolean isSuccess() {
		return success;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
