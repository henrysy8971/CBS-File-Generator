package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.constants.BatchMetricsConstants;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import com.silverlakesymmetri.cbs.fileGenerator.validation.XsdValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@StepScope
public class DynamicItemProcessor implements ItemProcessor<DynamicRecord, DynamicRecord> {
	private static final Logger logger = LoggerFactory.getLogger(DynamicItemProcessor.class);
	private final InterfaceConfigLoader interfaceConfigLoader;
	private final XsdValidator xsdValidator;

	private StepExecution stepExecution;
	private String activeXsdSchema;

	@Autowired
	public DynamicItemProcessor(
			InterfaceConfigLoader interfaceConfigLoader,
			@Autowired(required = false) XsdValidator xsdValidator) {
		this.interfaceConfigLoader = interfaceConfigLoader;
		this.xsdValidator = xsdValidator;
	}

	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.stepExecution = stepExecution;
		String interfaceType = stepExecution.getJobParameters().getString("interfaceType");

		// Initialize metrics in ExecutionContext if missing
		initializeMetric(BatchMetricsConstants.KEY_PROCESSED);
		initializeMetric(BatchMetricsConstants.KEY_SKIPPED);
		initializeMetric(BatchMetricsConstants.KEY_INVALID);

		try {
			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
			if (config != null) {
				String xsdSchemaFile = config.getXsdSchemaFile();
				if (xsdSchemaFile != null && xsdValidator != null && xsdValidator.schemaExists(xsdSchemaFile)) {
					this.activeXsdSchema = xsdSchemaFile;
					logger.info("XSD validation enabled for interface: {} (schema: {})",
							interfaceType, xsdSchemaFile);
				}
			}
		} catch (Exception e) {
			logger.warn("Error loading schema configuration for interface: {}", interfaceType, e);
		}
	}

	@Override
	public DynamicRecord process(DynamicRecord record) {
		try {
			if (record == null || record.size() == 0) {
				incrementMetric(BatchMetricsConstants.KEY_SKIPPED);
				logger.debug("Skipping empty record");
				return null;
			}

			Set<String> columns = record.getColumnNames();
			applyTransformations(record, columns);

			incrementMetric(BatchMetricsConstants.KEY_PROCESSED);
			logger.debug("Record processed successfully: {} columns", record.size());
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
			Object value = record.getValue(columnName);
			if (value instanceof String) {
				record.updateValue(columnName, ((String) value).trim());
			}
		}
	}

	// ================= Metrics =================
	private void initializeMetric(String key) {
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
		return stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_PROCESSED, 0L);
	}

	public long getSkippedCount() {
		return stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_SKIPPED, 0L);
	}

	public long getInvalidCount() {
		return stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_INVALID, 0L);
	}
}
