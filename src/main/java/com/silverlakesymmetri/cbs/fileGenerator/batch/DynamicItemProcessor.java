package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import com.silverlakesymmetri.cbs.fileGenerator.validation.XsdValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * DynamicItemProcessor with restart-safe metrics tracking.
 * Tracks processed, skipped, and invalid counts in StepExecutionContext.
 */
@Component
@StepScope
public class DynamicItemProcessor implements ItemProcessor<DynamicRecord, DynamicRecord> {

	private static final Logger logger = LoggerFactory.getLogger(DynamicItemProcessor.class);

	private static final String KEY_PROCESSED = "processedCount";
	private static final String KEY_SKIPPED = "skippedCount";
	private static final String KEY_INVALID = "invalidCount";

	@Autowired
	private InterfaceConfigLoader interfaceConfigLoader;

	@Autowired(required = false)
	private XsdValidator xsdValidator;

	private String xsdSchemaFile;
	private StepExecution stepExecution;

	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.stepExecution = stepExecution;

		String interfaceType = stepExecution.getJobParameters().getString("interfaceType");

		// Initialize counters in ExecutionContext if missing
		stepExecution.getExecutionContext().putLong(KEY_PROCESSED,
				stepExecution.getExecutionContext().getLong(KEY_PROCESSED, 0L));
		stepExecution.getExecutionContext().putLong(KEY_SKIPPED,
				stepExecution.getExecutionContext().getLong(KEY_SKIPPED, 0L));
		stepExecution.getExecutionContext().putLong(KEY_INVALID,
				stepExecution.getExecutionContext().getLong(KEY_INVALID, 0L));

		// Load optional XSD schema
		try {
			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
			if (config != null) {
				this.xsdSchemaFile = config.getXsdSchemaFile();
				if (xsdSchemaFile != null && xsdValidator != null && xsdValidator.schemaExists(xsdSchemaFile)) {
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
				incrementCount(KEY_SKIPPED);
				logger.warn("Record has no columns - skipping");
				return null;
			}

			applyTransformations(record);

			if (xsdSchemaFile != null && xsdValidator != null) {
				String xmlContent = convertRecordToXml(record);
				if (!xsdValidator.validateRecord(xmlContent, xsdSchemaFile)) {
					incrementCount(KEY_INVALID);
					logger.warn("XSD validation failed - skipping record");
					return null;
				}
			}

			incrementCount(KEY_PROCESSED);
			logger.debug("Record processed successfully: {} columns", record.size());
			return record;

		} catch (Exception e) {
			incrementCount(KEY_INVALID);
			logger.error("Error processing record - skipping", e);
			return null;
		}
	}

	// ---------------- Helpers ----------------

	private void applyTransformations(DynamicRecord record) {
		for (String columnName : record.getColumnNames()) {
			Object value = record.getValue(columnName);
			if (value instanceof String) {
				record.updateValue(columnName, ((String) value).trim());
			}
		}
	}

	private String convertRecordToXml(DynamicRecord record) {
		StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<record>\n");
		for (String columnName : record.getColumnNames()) {
			Object value = record.getValue(columnName);
			if (value != null) {
				String elementName = columnName.replaceAll("[^a-zA-Z0-9_]", "_");
				xml.append("  <").append(elementName).append(">")
						.append(escapeXml(value.toString()))
						.append("</").append(elementName).append(">\n");
			}
		}
		xml.append("</record>\n");
		return xml.toString();
	}

	private String escapeXml(String text) {
		return text.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&apos;");
	}

	private void incrementCount(String key) {
		long current = stepExecution.getExecutionContext().getLong(key, 0L);
		stepExecution.getExecutionContext().putLong(key, current + 1);
	}

	// ---------------- Public accessors for listeners ----------------

	public long getProcessedCount() {
		return stepExecution.getExecutionContext().getLong(KEY_PROCESSED, 0L);
	}

	public long getSkippedCount() {
		return stepExecution.getExecutionContext().getLong(KEY_SKIPPED, 0L);
	}

	public long getInvalidCount() {
		return stepExecution.getExecutionContext().getLong(KEY_INVALID, 0L);
	}
}
