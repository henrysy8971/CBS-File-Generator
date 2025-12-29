package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.service.FileFinalizationService;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * Job listener for safe finalization of .part files after batch completion.
 * Features:
 * - Atomic rename (.part → final)
 * - SHA256 checksum verification
 * - Restart-safe behavior
 * - Backward-compatible constructors
 */
public class DynamicJobExecutionListener implements JobExecutionListener {

	private static final Logger logger = LoggerFactory.getLogger(DynamicJobExecutionListener.class);

	private final FileFinalizationService fileFinalizationService;
	private final FileGenerationService fileGenerationService;

	/**
	 * Preferred constructor (new code)
	 */
	public DynamicJobExecutionListener(FileFinalizationService fileFinalizationService, FileGenerationService fileGenerationService) {
		this.fileFinalizationService = fileFinalizationService;
		this.fileGenerationService = fileGenerationService;
	}

	/**
	 * Legacy constructor for backward compatibility
	 */
	public DynamicJobExecutionListener(FileGenerationService fileGenerationService,
									   FileFinalizationService fileFinalizationService) {
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

		if (partFilePath == null) {
			logger.warn("No part file found in job context; skipping finalization");
			return;
		}

		// Handle FAILED or STOPPED jobs
		if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
			handleJobFailure(jobId, jobExecution, partFilePath);
			return;
		}

		try {
			logger.info("Finalizing part file: {}", partFilePath);

			// Atomic rename: .part → final
			boolean finalized = fileFinalizationService.finalizeFile(partFilePath);

			if (!finalized) {
				String msg = "Atomic rename failed for " + partFilePath;
				logger.error(msg);
				throw new RuntimeException(msg);
			}

			// SHA256 verification
			String finalFilePath = partFilePath.replaceAll("\\.part$", "");
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
			fileFinalizationService.cleanupPartFile(partFilePath);
		}
	}

	private void handleJobFailure(String jobId, JobExecution jobExecution, String partFilePath) {
		logger.warn("Job {} did not complete successfully. Cleaning up.", jobId);
		fileFinalizationService.cleanupPartFile(partFilePath);

		// Sync DB status with Spring Batch status
		fileGenerationService.markFailed(jobId, "Batch execution status: " + jobExecution.getStatus());
	}
}
