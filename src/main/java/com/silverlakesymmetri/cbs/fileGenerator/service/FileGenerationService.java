package com.silverlakesymmetri.cbs.fileGenerator.service;

import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.repository.FileGenerationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class FileGenerationService {

	private static final Logger logger = LoggerFactory.getLogger(FileGenerationService.class);

	@Autowired
	private FileGenerationRepository repository;

	// ==================== Create ====================
	public FileGeneration createFileGeneration(String fileName, String filePath, String createdBy, String interfaceType) {
		FileGeneration fg = new FileGeneration();
		fg.setJobId(UUID.randomUUID().toString());
		fg.setFileName(fileName);
		fg.setFilePath(filePath);
		fg.setStatus(FileGenerationStatus.PENDING.name());
		fg.setCreatedBy(createdBy);
		fg.setInterfaceType(interfaceType);
		fg.setCreatedDate(new Timestamp(System.currentTimeMillis()));

		FileGeneration saved = repository.save(fg);
		logger.info("File generation record created: jobId={}, fileName={}, interfaceType={}",
				saved.getJobId(), fileName, interfaceType);
		return saved;
	}

	// ==================== Status ====================

	public void markProcessing(String jobId) {
		updateStatus(jobId, FileGenerationStatus.PROCESSING, null);
	}

	public void markStopped(String jobId) {
		updateStatus(jobId, FileGenerationStatus.STOPPED, null);
	}

	public void markFinalizing(String jobId) {
		updateStatus(jobId, FileGenerationStatus.FINALIZING, null);
	}

	/**
	 * Mark job as completed with a retry policy.
	 * Retries up to 3 times with a 2-second delay if a transient DB error occurs.
	 */
	@Retryable(
			value = {
					org.springframework.dao.TransientDataAccessException.class,
					org.springframework.orm.ObjectOptimisticLockingFailureException.class
			},
			maxAttempts = 3,
			backoff = @Backoff(delay = 2000)
	)
	public void markCompleted(String jobId) {
		updateStatus(jobId, FileGenerationStatus.COMPLETED, null);
	}

	@Retryable(
			value = {org.springframework.dao.TransientDataAccessException.class},
			maxAttempts = 3,
			backoff = @Backoff(delay = 2000)
	)
	public void markFailed(String jobId, String errorMessage) {
		updateStatus(jobId, FileGenerationStatus.FAILED, errorMessage);
		logger.error("File generation failed: jobId={}, error={}", jobId, errorMessage);
	}

	/**
	 * Recover method: This runs if all 3 retry attempts fail.
	 */
	@Recover
	public void recover(Exception e, String jobId) {
		logger.error("CRITICAL: Final database update failed after retries for JobId: {}. Manual intervention required.", jobId, e);
		// Here you could send an alert to an IT support Slack channel or email
	}

	private void updateStatus(String jobId, FileGenerationStatus status, String errorMessage) {
		FileGeneration fg = getRequired(jobId);
		fg.setStatus(status.name());

		if (errorMessage != null) {
			fg.setErrorMessage(errorMessage);
		}

		if (status == FileGenerationStatus.COMPLETED
				|| status == FileGenerationStatus.FAILED) {
			fg.setCompletedDate(now());
		}

		repository.save(fg);
		logger.info("File generation status updated: jobId={}, status={}", jobId, status);
	}

	// ==================== Metrics ====================

	public void updateFileMetrics(
			String jobId,
			long processed,
			long skipped,
			long invalid) {

		FileGeneration fg = getRequired(jobId);
		fg.setRecordCount(processed);
		fg.setSkippedRecordCount(skipped);
		fg.setInvalidRecordCount(invalid);
		repository.save(fg);

		logger.info(
				"Metrics updated: jobId={}, processed={}, skipped={}, invalid={}",
				jobId, processed, skipped, invalid
		);
	}

	// ==================== Queries ====================

	@Transactional(readOnly = true)
	public Optional<FileGeneration> getFileGeneration(String jobId) {
		return repository.findByJobId(jobId);
	}

	@Transactional(readOnly = true)
	public List<FileGeneration> getPendingFileGenerations() {
		return repository.findByStatus(FileGenerationStatus.PENDING);
	}

	@Transactional(readOnly = true)
	public boolean hasRunningJob(String interfaceType) {
		return repository.existsByInterfaceTypeAndStatus(
				interfaceType,
				FileGenerationStatus.PROCESSING
		);
	}

	// ==================== Helpers ====================

	private FileGeneration getRequired(String jobId) {
		return repository.findByJobId(jobId)
				.orElseThrow(() ->
						new IllegalStateException("Job not found: " + jobId));
	}

	private Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}
}
