package com.silverlakesymmetri.cbs.fileGenerator.batch.listeners;

import com.silverlakesymmetri.cbs.fileGenerator.constants.FinalizationResult;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileFinalizationService;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Job listener for safe finalization of .part files after batch completion.
 * Features:
 * - Atomic rename (.part -> final)
 * - SHA256 checksum verification
 * - Restart-safe behavior
 * - Backward-compatible constructors
 */
public class JobExecutionListener implements org.springframework.batch.core.JobExecutionListener {
	private static final Logger logger = LoggerFactory.getLogger(JobExecutionListener.class);
	private final FileFinalizationService fileFinalizationService;
	private final FileGenerationService fileGenerationService;

	@Autowired
	public JobExecutionListener(FileFinalizationService fileFinalizationService, FileGenerationService fileGenerationService) {
		this.fileFinalizationService = fileFinalizationService;
		this.fileGenerationService = fileGenerationService;
	}

	@Override
	public void beforeJob(JobExecution jobExecution) {
		logger.debug("Job started: {}", jobExecution.getJobInstance().getJobName());
	}

	@Override
	public void afterJob(JobExecution jobExecution) {
		String jobId = jobExecution.getJobParameters().getString("jobId");
		String partFilePath = jobExecution.getExecutionContext().getString("partFilePath", null);
		String currentPath = partFilePath;

		if (partFilePath == null) {
			logger.warn("No part file found in job context; skipping finalization");
			return;
		}

		String finalFilePath = partFilePath.replaceAll("\\.part$", "");

		// Handle FAILED or STOPPED jobs
		if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
			handleJobFailure(jobId, jobExecution);
			return;
		}

		try {
			logger.info("Finalizing part file: {}", partFilePath);

			FinalizationResult finalizationResult = fileFinalizationService.finalizeFile(partFilePath);

			if (!FinalizationResult.SUCCESS.equals(finalizationResult)) {
				String msg = "Atomic rename failed for " + partFilePath + ". Reason - " + finalizationResult.toString();
				logger.error(msg);
				throw new RuntimeException(msg);
			}

			currentPath = finalFilePath;
			// SHA256 verification
			boolean verified = fileFinalizationService.verifyShaFile(finalFilePath);

			if (!verified) {
				String msg = "SHA256 checksum mismatch for " + finalFilePath;
				logger.error(msg);
				throw new RuntimeException(msg);
			}

			// Only mark as COMPLETED if all file operations succeeded
			fileGenerationService.markCompleted(jobId);
			logger.info("File finalized and verified successfully: {}", finalFilePath);
		} catch (Exception e) {
			logger.error("Finalization failed for JobId: {}", jobId, e);
			// Update DB to FAILED if post-processing fails
			fileGenerationService.markFailed(jobId, "Post-processing error: " + e.getMessage());
			// Cleanup the .part file if it still exists after a failed rename/verify
			fileFinalizationService.cleanupPartFile(currentPath);
		}
	}

	private void handleJobFailure(String jobId, JobExecution jobExecution) {
		try {
			fileGenerationService.markFailed(jobId, "Batch execution status: " + jobExecution.getStatus());
		} catch (IllegalStateException e) {
			logger.debug("Job {} was already marked as FAILED by a Step or Tasklet.", jobId);
		}
	}
}
