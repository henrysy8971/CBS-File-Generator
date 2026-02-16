package com.silverlakesymmetri.cbs.fileGenerator.constants;

public enum FinalizationResult {
	SUCCESS(true, false),
	INVALID_PART_FILE(false, false),
	SHA_GENERATION_FAILED(false, true),
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
