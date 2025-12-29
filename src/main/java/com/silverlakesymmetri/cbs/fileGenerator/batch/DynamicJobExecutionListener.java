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

	/**
	 * Preferred constructor (new code)
	 */
	public DynamicJobExecutionListener(FileFinalizationService fileFinalizationService) {
		this.fileFinalizationService = fileFinalizationService;
	}

	/**
	 * Legacy constructor for backward compatibility
	 */
	public DynamicJobExecutionListener(FileGenerationService fileGenerationService,
									   FileFinalizationService fileFinalizationService) {
		this.fileFinalizationService = fileFinalizationService;
	}

	@Override
	public void beforeJob(JobExecution jobExecution) {
		logger.debug("Job started: {}", jobExecution.getJobInstance().getJobName());
	}

	@Override
	public void afterJob(JobExecution jobExecution) {
		String partFilePath = jobExecution.getExecutionContext().getString("partFilePath", null);

		if (partFilePath == null) {
			logger.warn("No part file found in job context; skipping finalization");
			return;
		}

		// Handle failed or stopped jobs: cleanup partial file
		if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
			logger.warn("Job not completed (status={}); cleaning up part file: {}",
					jobExecution.getStatus(), partFilePath);
			try {
				fileFinalizationService.cleanupPartFile(partFilePath);
			} catch (Exception e) {
				logger.error("Error during cleanup of part file: {}", partFilePath, e);
			}
			return;
		}

		logger.info("Finalizing part file: {}", partFilePath);

		try {
			// Atomic rename: .part → final
			boolean finalized = fileFinalizationService.finalizeFile(partFilePath);

			if (!finalized) {
				String msg = "File finalization failed for " + partFilePath;
				logger.error(msg);
				throw new RuntimeException(msg);
			}

			// SHA256 verification
			String finalFilePath = partFilePath.replaceAll("\\.part$", "");
			boolean verified = fileFinalizationService.verifyShaFile(finalFilePath);

			if (!verified) {
				String msg = "SHA256 verification failed for finalized file: " + finalFilePath;
				logger.error(msg);
				throw new RuntimeException(msg);
			}

			logger.info("File finalized and verified successfully: {}", finalFilePath);

		} catch (Exception e) {
			logger.error("Critical exception during finalization of part file: {}", partFilePath, e);
			throw new RuntimeException("Critical exception during file finalization", e);
		}
	}
}
