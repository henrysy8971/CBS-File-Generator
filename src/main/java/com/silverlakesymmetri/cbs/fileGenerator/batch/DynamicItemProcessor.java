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
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@StepScope
public class DynamicItemProcessor implements ItemProcessor<DynamicRecord, DynamicRecord> {
	private static final Logger logger = LoggerFactory.getLogger(DynamicItemProcessor.class);
	private StepExecution stepExecution;

	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.stepExecution = stepExecution;
		// Initialize metrics
		initializeMetric(BatchMetricsConstants.KEY_PROCESSED);
		initializeMetric(BatchMetricsConstants.KEY_SKIPPED);
		initializeMetric(BatchMetricsConstants.KEY_INVALID);
	}

	@Override
	public DynamicRecord process(DynamicRecord record) {
		try {
			if (record == null || record.isEmpty()) {
				incrementMetric(BatchMetricsConstants.KEY_SKIPPED);
				logger.debug("Skipping empty record");
				return null;
			}

			Set<String> columns = record.keySet();
			applyTransformations(record, columns);

			incrementMetric(BatchMetricsConstants.KEY_PROCESSED);
			return record;

		} catch (Exception e) {
			incrementMetric(BatchMetricsConstants.KEY_INVALID);
			logger.error("Error processing record - skipping", e);
			return null;
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
		if (stepExecution == null) {
			return;
		}
		if (!stepExecution.getExecutionContext().containsKey(key)) {
			stepExecution.getExecutionContext().putLong(key, 0L);
		}
	}

	private void incrementMetric(String key) {
		if (stepExecution == null) {
			return;
		}
		ExecutionContext ctx = stepExecution.getExecutionContext();
		ctx.putLong(key, ctx.getLong(key, 0L) + 1);
	}

	// ================= Metrics Accessors =================
	public long getProcessedCount() {
		if (stepExecution == null) {
			return 0;
		}
		return stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_PROCESSED, 0L);
	}

	public long getSkippedCount() {
		if (stepExecution == null) {
			return 0;
		}
		return stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_SKIPPED, 0L);
	}

	public long getInvalidCount() {
		if (stepExecution == null) {
			return 0;
		}
		return stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_INVALID, 0L);
	}
}
