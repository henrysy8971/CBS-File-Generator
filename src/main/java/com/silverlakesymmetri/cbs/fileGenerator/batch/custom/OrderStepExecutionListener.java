package com.silverlakesymmetri.cbs.fileGenerator.batch.custom;

import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.listener.StepExecutionListenerSupport;
import org.springframework.batch.item.ExecutionContext;

/**
 * Step execution listener for Order batch processing.
 * Captures order writer state (record count, file path) for job listener.
 */
public class OrderStepExecutionListener extends StepExecutionListenerSupport {

	private static final Logger logger =
			LoggerFactory.getLogger(OrderStepExecutionListener.class);

	private final OrderItemWriter orderItemWriter;
	private final FileGenerationService fileGenerationService;

	public OrderStepExecutionListener(
			OrderItemWriter orderItemWriter,
			FileGenerationService fileGenerationService) {
		this.orderItemWriter = orderItemWriter;
		this.fileGenerationService = fileGenerationService;
	}

	@Override
	public void beforeStep(StepExecution stepExecution) {
		logger.debug("Order step execution started: {}", stepExecution.getStepName());
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		String jobId = stepExecution.getJobParameters().getString("jobId");
		ExecutionContext stepContext = stepExecution.getExecutionContext();

		// Determine Step Success
		// A step is successful if its status is COMPLETED and no exceptions occurred.
		boolean isSuccess = !stepExecution.getStatus().isUnsuccessful()
				&& stepExecution.getFailureExceptions().isEmpty();

		try {
			// Notify Writer of success status
			// This allows the writer to decide whether to write XML footers and finalize the file.
			if (orderItemWriter != null) {
				orderItemWriter.setStepSuccessful(isSuccess);

				String partFilePath = orderItemWriter.getPartFilePath();
				if (partFilePath != null) {
					// Store in job execution context (accessible to job listener)
					stepExecution.getJobExecution().getExecutionContext()
							.put("partFilePath", partFilePath);

					// Store record count
					long recordCount = orderItemWriter.getRecordCount();
					stepExecution.getJobExecution().getExecutionContext()
							.put("recordCount", recordCount);

					logger.info("Order step execution completed. Part file path stored: {}, Record count: {}",
							partFilePath, recordCount);
				}
			}

			// Extract metrics (Keys must match DynamicItemProcessor constants)
			long processed = stepContext.getLong("processedCount", 0L);
			long skipped = stepContext.getLong("skippedCount", 0L);
			long invalid = stepContext.getLong("invalidCount", 0L);

			if (jobId != null) {
				fileGenerationService.updateFileMetrics(jobId, processed, skipped, invalid);
				logger.info("Step metrics persisted: jobId={}, processed={}, skipped={}, invalid={}",
						jobId, processed, skipped, invalid);
			}

		} catch (Exception e) {
			logger.error("Error capturing part file path in order step listener", e);
		}

		return stepExecution.getExitStatus();
	}
}
