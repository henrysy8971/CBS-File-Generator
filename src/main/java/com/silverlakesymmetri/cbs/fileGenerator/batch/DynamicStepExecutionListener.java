package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.constants.BatchMetricsConstants;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.listener.StepExecutionListenerSupport;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

public class DynamicStepExecutionListener extends StepExecutionListenerSupport {
	private static final Logger logger =
			LoggerFactory.getLogger(DynamicStepExecutionListener.class);

	private final DynamicItemWriter dynamicItemWriter;
	private final FileGenerationService fileGenerationService;

	@Autowired
	public DynamicStepExecutionListener(
			DynamicItemWriter dynamicItemWriter,
			FileGenerationService fileGenerationService) {
		this.dynamicItemWriter = dynamicItemWriter;
		this.fileGenerationService = fileGenerationService;
	}

	@Override
	public void beforeStep(StepExecution stepExecution) {
		logger.debug("Starting step: {}", stepExecution.getStepName());
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		String jobId = stepExecution.getJobParameters().getString("jobId");
		ExecutionContext stepContext = stepExecution.getExecutionContext();
		ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();

		// Determine Step Success
		// A step is successful if its status is COMPLETED and no exceptions occurred.
		boolean isSuccess = !stepExecution.getStatus().isUnsuccessful()
				&& stepExecution.getFailureExceptions().isEmpty();

		try {
			// Notify Writer of success status
			// This allows the writer to decide whether to write XML footers and finalize the file.
			if (dynamicItemWriter != null) {
				dynamicItemWriter.setStepSuccessful(isSuccess);

				// Hand off the .part file path to the Job Execution Context
				// for SHA-256 calculation
				// This is critical for DynamicJobExecutionListener to find the file
				String partPath = dynamicItemWriter.getPartFilePath();
				if (partPath != null) {
					jobContext.putString("partFilePath", partPath);
					logger.debug("Handed off partFilePath to JobContext: {}", partPath);
				}
			}

			// Extract and Persist Standardized Metrics
			// Keys here MUST match OrderItemProcessor/DynamicItemProcessor constants
			long processed = stepContext.getLong(BatchMetricsConstants.KEY_PROCESSED, 0L);
			long skipped = stepContext.getLong(BatchMetricsConstants.KEY_SKIPPED, 0L);
			long invalid = stepContext.getLong(BatchMetricsConstants.KEY_INVALID, 0L);

			if (jobId != null) {
				fileGenerationService.updateFileMetrics(jobId, processed, skipped, invalid);
				logger.info("Step metrics persisted: jobId={}, processed={}, skipped={}, invalid={}",
						jobId, processed, skipped, invalid);

				// Store total record count at job level for logging/summary
				jobContext.putLong("totalRecordCount", processed);

				logger.info("Order metrics synced: jobId={}, processed={}, skipped={}, invalid={}",
						jobId, processed, skipped, invalid);
			}

		} catch (Exception e) {
			// We catch so the job doesn't fail purely because of a logging/metrics blip
			logger.error("Non-critical error syncing metrics for step {}", stepExecution.getStepName(), e);
		}

		return stepExecution.getExitStatus();
	}
}
