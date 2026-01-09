package com.silverlakesymmetri.cbs.fileGenerator.constants;

/**
 * Centralized constants for Spring Batch processing metrics.
 */
public final class BatchMetricsConstants {

	// Prevent instantiation
	private BatchMetricsConstants() { }

	public static final String KEY_PROCESSED = "processedCount";
	public static final String KEY_SKIPPED = "skippedCount";
	public static final String KEY_INVALID = "invalidCount";
}
