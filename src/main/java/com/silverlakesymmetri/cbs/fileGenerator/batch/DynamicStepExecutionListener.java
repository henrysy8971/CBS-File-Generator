package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.listener.StepExecutionListenerSupport;

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
		try {
			// Capture .part file metadata
			if (dynamicItemWriter != null) {
				String partFilePath = dynamicItemWriter.getPartFilePath();
				if (partFilePath != null && partFilePath.endsWith(".part")) {
					stepExecution.getJobExecution()
							.getExecutionContext()
							.putString("partFilePath", partFilePath);

					logger.info("Captured .part file for finalization: {}", partFilePath);
				}
			}

			// Persist record metrics to FileGenerationService
			String jobId = stepExecution.getJobParameters().getString("jobId");
			if (jobId != null && dynamicItemWriter != null) {

				long processed = 0L;
				long skipped = 0L;
				long invalid = 0L;

				// If DynamicItemProcessor is step-scoped, retrieve counts from ExecutionContext
				Object processorObj = stepExecution.getExecutionContext().get("itemProcessor");
				if (processorObj instanceof com.silverlakesymmetri.cbs.fileGenerator.batch.DynamicItemProcessor) {
					com.silverlakesymmetri.cbs.fileGenerator.batch.DynamicItemProcessor processor =
							(com.silverlakesymmetri.cbs.fileGenerator.batch.DynamicItemProcessor) processorObj;

					processed = processor.getProcessedCount();
					skipped = processor.getSkippedCount();
					invalid = processor.getInvalidCount();
				}

				fileGenerationService.updateFileMetrics(jobId, processed, skipped, invalid);
				logger.info("Step metrics persisted: jobId={}, processed={}, skipped={}, invalid={}",
						jobId, processed, skipped, invalid);
			}

		} catch (Exception e) {
			logger.error("Failed to persist step metrics or .part file metadata for step {}",
					stepExecution.getStepName(), e);
		}

		return stepExecution.getExitStatus() != null
				? stepExecution.getExitStatus()
				: ExitStatus.COMPLETED;
	}
}
