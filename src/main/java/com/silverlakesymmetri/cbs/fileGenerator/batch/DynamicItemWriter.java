package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Production-ready DynamicItemWriter with restart-safety and .part file continuation.
 * Ensures:
 * - Proper flush/close for large files
 * - Restart-safety using ExecutionContext
 * - Compatible with DynamicItemReader for arbitrarily large datasets
 */
@Component
@StepScope
public class DynamicItemWriter implements OutputFormatWriter {

	private static final Logger logger = LoggerFactory.getLogger(DynamicItemWriter.class);

	private static final String CONTEXT_KEY_PART_FILE = "dynamic.writer.partFilePath";
	private static final String CONTEXT_KEY_RECORD_COUNT = "dynamic.writer.recordCount";

	@Autowired
	private OutputFormatWriterFactory writerFactory;

	private OutputFormatWriter delegateWriter;
	private String interfaceType;
	private String outputFilePath;

	private ExecutionContext stepContext;

	/**
	 * Step-scoped initialization.
	 * Determines which writer to use and initializes it.
	 */
	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.interfaceType = stepExecution.getJobParameters().getString("interfaceType");
		this.outputFilePath = stepExecution.getJobParameters().getString("outputFilePath");
		this.stepContext = stepExecution.getExecutionContext();

		if (interfaceType == null || interfaceType.trim().isEmpty()) {
			throw new IllegalArgumentException("Job parameter 'interfaceType' is required");
		}
		if (outputFilePath == null || outputFilePath.trim().isEmpty()) {
			throw new IllegalArgumentException("Job parameter 'outputFilePath' is required");
		}

		try {
			// Select delegate writer based on interface configuration
			this.delegateWriter = writerFactory.selectWriter(interfaceType);

			// Check if restarting: restore part file path and record count
			String existingPartFile = stepContext.getString(CONTEXT_KEY_PART_FILE, null);
			long existingCount = stepContext.getLong(CONTEXT_KEY_RECORD_COUNT, 0);

			if (existingPartFile != null) {
				// Resume existing .part file
				delegateWriter.init(existingPartFile, interfaceType);
				logger.info("Resuming writer from existing part file: {}, current record count={}",
						existingPartFile, existingCount);
			} else {
				// Start new .part file
				delegateWriter.init(outputFilePath, interfaceType);
				logger.info("Initialized new writer for interface={}, outputFile={}",
						interfaceType, outputFilePath);
			}

		} catch (Exception e) {
			logger.error("Error initializing DynamicItemWriter for interface {}", interfaceType, e);
			throw new RuntimeException("Failed to initialize DynamicItemWriter", e);
		}
	}

	/**
	 * Write items to the delegate writer.
	 * Updates record count in ExecutionContext for restart-safety.
	 */
	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (delegateWriter == null) {
			throw new IllegalStateException("Writer not initialized. Did @BeforeStep execute?");
		}
		if (items == null || items.isEmpty()) return;

		delegateWriter.write(items);

		// Update restart state
		stepContext.putString(CONTEXT_KEY_PART_FILE, delegateWriter.getPartFilePath());
		stepContext.putLong(CONTEXT_KEY_RECORD_COUNT, delegateWriter.getRecordCount());
	}

	/**
	 * Close the delegate writer and ensure resources are released.
	 */
	@Override
	public void close() throws Exception {
		if (delegateWriter != null) {
			try {
				delegateWriter.close();
				logger.info("DynamicItemWriter closed successfully - interface={}, totalRecords={}",
						interfaceType, delegateWriter.getRecordCount());
			} catch (Exception e) {
				logger.error("Error closing DynamicItemWriter delegate for interface {}", interfaceType, e);
				throw e;
			}
		}
	}

	/**
	 * No-op: handled in beforeStep
	 */
	@Override
	public void init(String outputFilePath, String interfaceType) {
		// No-op: initialization handled in @BeforeStep
	}

	@Override
	public long getRecordCount() {
		return delegateWriter != null ? delegateWriter.getRecordCount() : 0;
	}

	@Override
	public String getPartFilePath() {
		return delegateWriter != null ? delegateWriter.getPartFilePath() : null;
	}
}
