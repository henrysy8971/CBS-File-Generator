package com.silverlakesymmetri.cbs.fileGenerator.batch.listeners;

import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.FILE_GEN_PART_FILE_PATH;
import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.FILE_GEN_TOTAL_RECORD_COUNT;

@Component
public class FileGenerationStepListener implements StepExecutionListener {
	private static final Logger logger = LoggerFactory.getLogger(FileGenerationStepListener.class);
	private final FileGenerationService fileGenerationService;

	public FileGenerationStepListener(FileGenerationService fileGenerationService) {
		this.fileGenerationService = Objects.requireNonNull(fileGenerationService, "fileGenerationService must not be null");
	}

	@Override
	public void beforeStep(StepExecution stepExecution) {
		logger.debug("Starting step: {}", stepExecution.getStepName());
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		String jobId = stepExecution.getJobParameters().getString("jobId");
		ExecutionContext jobContext = Objects.requireNonNull(stepExecution.getJobExecution().getExecutionContext());

		long duration = 0;
		if (stepExecution.getStartTime() != null && stepExecution.getEndTime() != null) {
			duration = stepExecution.getEndTime().getTime() - stepExecution.getStartTime().getTime();
		} else {
			logger.warn("Step {} missing start or end time; duration set to 0", stepExecution.getStepName());
		}

		logger.info("Step {} completed in {} ms [jobId={}]", stepExecution.getStepName(), duration, jobId);

		long readCount = stepExecution.getReadCount();
		long processed = stepExecution.getWriteCount();
		long skipped = stepExecution.getProcessSkipCount();
		long invalid = stepExecution.getWriteSkipCount() + stepExecution.getFilterCount();

		logger.info("Step {} metrics: read={}, processed={}, skipped={}, invalid={}, duration={}ms, jobId={}",
				stepExecution.getStepName(), readCount, processed, skipped, invalid, duration, jobId);

		try {
			if (jobId != null) {
				fileGenerationService.updateFileMetrics(jobId, processed, skipped, invalid);
			} else {
				logger.warn("Step {}: jobId is null; skipping metrics persistence", stepExecution.getStepName());
			}
		} catch (Exception e) {
			logger.error("Failed to update metrics for step {} [jobId={}]: read={}, processed={}, skipped={}, invalid={}",
					stepExecution.getStepName(), jobId, readCount, processed, skipped, invalid, e);
		}

		// Accumulate instead of overwrite to support multi-step generation
		long previousTotal = jobContext.getLong(FILE_GEN_TOTAL_RECORD_COUNT, 0L);
		jobContext.putLong(FILE_GEN_TOTAL_RECORD_COUNT, previousTotal + processed);

		// Ensure the Writer's partFilePath is promoted to the Job level
		// so the JobListener can see it for finalization.
		String partFilePath = stepExecution.getExecutionContext().getString(FILE_GEN_PART_FILE_PATH);
		if (partFilePath != null) {
			jobContext.putString(FILE_GEN_PART_FILE_PATH, partFilePath);
		}

		if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
			logger.warn("Step {} completed with status: {}", stepExecution.getStepName(), stepExecution.getStatus());
		}

		return stepExecution.getStatus() == BatchStatus.COMPLETED ? ExitStatus.COMPLETED : ExitStatus.FAILED;
	}
}
