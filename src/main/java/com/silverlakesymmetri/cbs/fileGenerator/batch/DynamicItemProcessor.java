package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.constants.BatchMetricsConstants;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@StepScope
public class DynamicItemProcessor implements ItemProcessor<DynamicRecord, DynamicRecord> {
	private static final Logger logger = LoggerFactory.getLogger(DynamicItemProcessor.class);
	private StepExecution stepExecution;

	// Circuit Breaker: If more than this many records fail, abort the job.
	// Default to 100 or configure via properties.
	@Value("${file.generation.processor.max-skip-count:100}")
	private int maxSkipCount;

	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.stepExecution = stepExecution;
		// Initialize metrics
		initializeMetric(BatchMetricsConstants.KEY_PROCESSED);
		initializeMetric(BatchMetricsConstants.KEY_SKIPPED);
		// Note: KEY_SKIPPED is now tracked by Spring Batch natively,
		// but we keep KEY_INVALID for business-specific validation tracking.
		initializeMetric(BatchMetricsConstants.KEY_INVALID);
	}

	@Override
	public DynamicRecord process(DynamicRecord record) {
		try {
			// 1. Validate Input (Empty Record)
			// Return null here to FILTER (not count as error/skip)
			if (record == null || record.isEmpty()) {
				logger.debug("Skipping empty record");
				return null;
			}

			// 2. Transformation Logic
			Set<String> columns = record.keySet();
			applyTransformations(record, columns);

			// 3. Success
			incrementMetric(BatchMetricsConstants.KEY_PROCESSED);
			return record;

		} catch (Exception e) {
			// 4. Handling Failures
			long invalidCount = incrementMetric(BatchMetricsConstants.KEY_INVALID);

			// Log context to help debugging (e.g. print the record content if possible)
			logger.error("Error processing record: {}", record, e);

			// 5. Circuit Breaker
			// If we exceed the limit, we throw a FATAL exception (RuntimeException)
			// that is NOT skipped by default configuration, crashing the job.
			if (invalidCount > maxSkipCount) {
				circuitBreak(invalidCount);
			}

			// 6. Trigger Skip
			// By re-throwing, we tell Spring Batch to:
			//   a. Rollback the transaction (if needed)
			//   b. Increment the Skip Count
			//   c. Continue to the next item
			throw new RuntimeException("Skipping invalid record", e);
		}
	}

	// ---------------- Helpers ----------------
	private void applyTransformations(DynamicRecord record, Set<String> columns) {
		for (String columnName : columns) {
			Object value = record.get(columnName);
			if (value instanceof String) {
				record.setValue(columnName, ((String) value).trim());
			}
		}
	}

	// ================= Metrics =================
	private void initializeMetric(String key) {
		if (stepExecution != null &&
				!stepExecution.getExecutionContext().containsKey(key)) {
			stepExecution.getExecutionContext().putLong(key, 0L);
		}
	}

	private long incrementMetric(String key) {
		if (stepExecution == null) return 0;
		ExecutionContext ctx = stepExecution.getExecutionContext();
		long newValue = ctx.getLong(key, 0L) + 1;
		ctx.putLong(key, newValue);
		return newValue;
	}

	// ================= Metrics Accessors =================
	public long getProcessedCount() {
		return stepExecution != null ?
				stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_PROCESSED, 0L) : 0;
	}

	public long getSkippedCount() {
		return stepExecution != null ?
				stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_INVALID, 0L) : 0;
	}

	public long getInvalidCount() {
		return stepExecution != null ? stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_INVALID, 0L) : 0;
	}

	private void circuitBreak(long count) {
		String errorMsg = String.format("Too many processing errors (%d). Aborting job to prevent silent failure.", count);
		logger.error(errorMsg);
		// Throwing a specialized exception or RuntimeException here.
		// Ideally, ensure this exception class is NOT in the .skip() list in BatchConfig.
		throw new IllegalStateException(errorMsg);
	}
}
