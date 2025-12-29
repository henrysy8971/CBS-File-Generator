package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.listener.StepExecutionListenerSupport;
import org.springframework.batch.item.ExecutionContext;

/**
 * Step listener responsible for:
 * 1. Capturing .part file metadata for finalization
 * 2. Persisting processed/skipped/invalid counts to FileGenerationService
 */
public class DynamicStepExecutionListener extends StepExecutionListenerSupport {

	private static final Logger logger =
			LoggerFactory.getLogger(DynamicStepExecutionListener.class);

	private final DynamicItemWriter dynamicItemWriter;
	private final FileGenerationService fileGenerationService;

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

		// Determine Step Success
		// A step is successful if its status is COMPLETED and no exceptions occurred.
		boolean isSuccess = !stepExecution.getStatus().isUnsuccessful()
				&& stepExecution.getFailureExceptions().isEmpty();

		try {
			// Notify Writer of success status
			// This allows the writer to decide whether to write XML footers and finalize the file.
			if (dynamicItemWriter != null) {
				dynamicItemWriter.setStepSuccessful(isSuccess);

				// Hand off the .part file path to the Job Execution Context for SHA-256 calculation
				String partPath = dynamicItemWriter.getPartFilePath();
				if (partPath != null) {
					stepExecution.getJobExecution().getExecutionContext().putString("partFilePath", partPath);
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
			// We catch so the job doesn't fail purely because of a logging/metrics blip
			logger.error("Non-critical error syncing metrics for step {}", stepExecution.getStepName(), e);
		}

		return stepExecution.getExitStatus();
	}
}
