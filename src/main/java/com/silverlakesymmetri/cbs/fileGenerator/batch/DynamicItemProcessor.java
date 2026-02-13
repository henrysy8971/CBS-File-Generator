package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.validator.ValidationException;
import org.springframework.stereotype.Component;

@Component
@StepScope
public class DynamicItemProcessor implements ItemProcessor<DynamicRecord, DynamicRecord> {
	private static final Logger logger = LoggerFactory.getLogger(DynamicItemProcessor.class);

	@Override
	public DynamicRecord process(DynamicRecord record) throws Exception {
		// 1. Structural Check (Filter vs. Skip)
		// If the record is truly null or empty, we "filter" it by returning null.
		// This increments 'filterCount' but does NOT count against 'skipLimit'.
		if (record == null || record.isEmpty()) {
			logger.debug("Filtering empty or null record");
			return null;
		}

		try {
			// 2. Data Transformation (Sanitization)
			applyTransformations(record);

			// 3. Validation
			// For dynamic records, this might check for mandatory columns
			validateDynamicContent(record);

			logger.debug("Successfully processed dynamic record");
			return record;

		} catch (ValidationException ve) {
			// This triggers 'processSkipCount' and the skipLimit in BatchConfig
			logger.warn("Skipping dynamic record due to validation failure: {}", ve.getMessage());
			throw ve;
		} catch (Exception e) {
			// Catastrophic or unexpected error
			logger.error("Critical error processing dynamic record: {}", record, e);
			throw e;
		}
	}

	/**
	 * Iterates through all columns and trims String values.
	 */
	private void applyTransformations(DynamicRecord record) {
		record.keySet().forEach(columnName -> {
			Object value = record.get(columnName);
			if (value instanceof String) {
				record.setValue(columnName, StringUtils.trim((String) value));
			}
		});
	}

	/**
	 * Generic validation for dynamic records.
	 * You can add logic here to check for 'must-have' columns based on config.
	 */
	private void validateDynamicContent(DynamicRecord record) {
		// Example: If your system requires every record to have a 'ID' column
		if (!record.containsKey("ID") && !record.containsKey("id")) {
			// throw new ValidationException("Record missing primary identifier (ID)");
		}
	}
}
