package com.silverlakesymmetri.cbs.fileGenerator.service;

import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.exception.ConflictException;
import com.silverlakesymmetri.cbs.fileGenerator.exception.LifecycleException;
import com.silverlakesymmetri.cbs.fileGenerator.repository.FileGenerationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
public class FileGenerationService {
	private static final Logger logger = LoggerFactory.getLogger(FileGenerationService.class);
	private final FileGenerationRepository fileGenerationRepository;

	@Autowired
	public FileGenerationService(FileGenerationRepository fileGenerationRepository) {
		this.fileGenerationRepository = fileGenerationRepository;
	}

	// ==================== Create ====================
	public FileGeneration createFileGeneration(String fileName,
											   String filePath,
											   String createdBy,
											   String interfaceType) {
		try {
			FileGeneration fg = new FileGeneration();
			fg.setJobId(UUID.randomUUID().toString());
			fg.setFileName(fileName);
			fg.setFilePath(filePath);
			fg.setStatus(FileGenerationStatus.PENDING);
			fg.setCreatedBy(createdBy);
			fg.setInterfaceType(interfaceType);
			fg.setCreatedDate(new Timestamp(System.currentTimeMillis()));

			FileGeneration saved = fileGenerationRepository.save(fg);

			logger.info("File generation record created: jobId={}, fileName={}, interfaceType={}",
					saved.getJobId(), fileName, interfaceType);

			// STRUCTURED LOGGING for Creation
			logger.info("[JOB_CREATED] jobId='{}' interface='{}' fileName='{}' user='{}'",
					saved.getJobId(),
					interfaceType,
					fileName,
					createdBy);

			return saved;
		} catch (DataIntegrityViolationException e) {
			// DB-level enforcement: unique active job per interface
			throw new ConflictException(
					"A job for this interface is already running", e
			);
		}
	}

	/* ===================== STATUS ===================== */

	@Transactional
	public void markProcessing(String jobId) {
		transitionStatus(jobId, FileGenerationStatus.PROCESSING, null);
	}

	@Transactional
	public void markFinalizing(String jobId) {
		transitionStatus(jobId, FileGenerationStatus.FINALIZING, null);
	}

	@Retryable(
			value = {
					org.springframework.dao.TransientDataAccessException.class,
					org.springframework.orm.ObjectOptimisticLockingFailureException.class
			},
			maxAttempts = 3,
			backoff = @Backoff(delay = 2000)
	)
	@Transactional
	public void markCompleted(String jobId) {
		transitionStatus(jobId, FileGenerationStatus.COMPLETED, null);
	}

	@Retryable(
			value = {
					org.springframework.dao.TransientDataAccessException.class,
					org.springframework.orm.ObjectOptimisticLockingFailureException.class
			},
			maxAttempts = 3,
			backoff = @Backoff(delay = 2000)
	)
	@Transactional
	public void markFailed(String jobId, String errorMessage) {
		transitionStatus(jobId, FileGenerationStatus.FAILED, errorMessage);
		logger.warn("File generation failed: jobId={}, error={}", jobId, errorMessage);
	}

	@Transactional(readOnly = true)
	public Page<FileGeneration> getFilesByStatus(FileGenerationStatus status, Pageable pageable) {
		return fileGenerationRepository.findAllByStatus(status, pageable);
	}

	@Recover
	public void recover(Exception e, String jobId) {
		logger.error("CRITICAL: Failed after retries for jobId={}", jobId, e);
		// Here you could send an alert to an IT support Slack channel or email
	}

	// Recovery for markCompleted
	@Recover
	public void recoverCompleted(Exception e, String jobId) {
		logger.error("CRITICAL: Failed to mark COMPLETED after retries for jobId={}", jobId, e);
	}

	// Recovery for markFailed (Matches parameters)
	@Recover
	public void recoverFailed(Exception e, String jobId, String errorMessage) {
		logger.error("CRITICAL: Failed to mark FAILED after retries for jobId={}. Original Error: {}", jobId, errorMessage, e);
	}

	private void transitionStatus(String jobId, FileGenerationStatus nextStatus, String errorMessage) {
		// 1. Fetch current status for the log
		FileGenerationStatus currentStatus = fileGenerationRepository.findStatusByJobId(jobId)
				.orElseThrow(() -> new LifecycleException("Job not found: " + jobId));

		// Prevent updates to terminal jobs
		if (currentStatus.isTerminal()) {
			throw new LifecycleException("Cannot update terminal job. jobId=" + jobId + ", status=" + currentStatus);
		}

		// Enforce lifecycle rules
		if (!currentStatus.canTransitionTo(nextStatus)) {
			throw new LifecycleException("Invalid status transition: " + currentStatus + " -> " + nextStatus + " for jobId=" + jobId);
		}

		Timestamp completedDate = nextStatus.isTerminal() ? now() : null;
		int updated = fileGenerationRepository.updateStatusAtomic(
				jobId,
				nextStatus,
				currentStatus,
				errorMessage,
				completedDate);

		if (updated == 0) {
			throw new ObjectOptimisticLockingFailureException(FileGeneration.class, jobId);
		}

		// 2. STRUCTURED LOGGING
		// We use a key-value format that is easy to grep or parse into JSON
		logger.info("[STATUS_CHANGE] jobId='{}' prevStatus='{}' nextStatus='{}' error='{}'",
				jobId,
				currentStatus,
				nextStatus,
				errorMessage != null ? errorMessage : "NONE");
	}

	/* ===================== METRICS ===================== */

	@Retryable(
			value = {
					org.springframework.dao.TransientDataAccessException.class,
					org.springframework.orm.ObjectOptimisticLockingFailureException.class
			},
			maxAttempts = 3,
			backoff = @Backoff(delay = 1000)
	)
	@Transactional
	public void updateFileMetrics(String jobId, long processed, long skipped, long invalid) {

		FileGenerationStatus status = fileGenerationRepository.findStatusByJobId(jobId)
				.orElseThrow(() -> new LifecycleException("Job not found: " + jobId));

		if (status.isTerminal()) {
			throw new LifecycleException(
					"Cannot update metrics for terminal job: " + jobId
			);
		}

		fileGenerationRepository.updateMetrics(jobId, processed, skipped, invalid);

		logger.info(
				"Metrics updated: jobId={}, processed={}, skipped={}, invalid={}",
				jobId, processed, skipped, invalid
		);
	}

	/* ===================== Queries ===================== */

	@Transactional(readOnly = true)
	public Optional<FileGeneration> getFileGeneration(String jobId) {
		return fileGenerationRepository.findByJobId(jobId);
	}

	@Transactional(readOnly = true)
	public List<FileGeneration> getPendingFileGenerations() {
		return fileGenerationRepository.findByStatus(FileGenerationStatus.PENDING);
	}

	@Transactional(readOnly = true)
	public List<FileGeneration> getProcessingFileGenerations() {
		return fileGenerationRepository.findByStatus(FileGenerationStatus.PROCESSING);
	}

	@Transactional(readOnly = true)
	public List<FileGeneration> getStoppedFileGenerations() {
		return fileGenerationRepository.findByStatus(FileGenerationStatus.STOPPED);
	}

	@Transactional(readOnly = true)
	public List<FileGeneration> getFailedFileGenerations() {
		return fileGenerationRepository.findByStatus(FileGenerationStatus.FAILED);
	}

	@Transactional(readOnly = true)
	public List<FileGeneration> getCompletedFileGenerations() {
		return fileGenerationRepository.findByStatus(FileGenerationStatus.COMPLETED);
	}

	@Transactional(readOnly = true)
	public boolean hasRunningJob(String interfaceType) {
		return fileGenerationRepository.existsByInterfaceTypeAndStatus(
				interfaceType,
				FileGenerationStatus.PROCESSING
		);
	}

	/* ===================== Helpers ===================== */

	private Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}

	public long getPendingCount() {
		// Assuming "PENDING" or "IN_PROGRESS" are your status strings
		return fileGenerationRepository.countByStatus(FileGenerationStatus.PENDING);
	}

	@Transactional(readOnly = true)
	public Page<FileGeneration> getProcessingFileGenerations(Pageable pageable) {
		return getFilesByStatus(FileGenerationStatus.PROCESSING, pageable);
	}

	@Transactional(readOnly = true)
	public Page<FileGeneration> getPendingFileGenerations(Pageable pageable) {
		return getFilesByStatus(FileGenerationStatus.PENDING, pageable);
	}

	@Transactional(readOnly = true)
	public Page<FileGeneration> getCompletedFileGenerations(Pageable pageable) {
		return getFilesByStatus(FileGenerationStatus.COMPLETED, pageable);
	}

	@Transactional(readOnly = true)
	public Page<FileGeneration> getStoppedFileGenerations(Pageable pageable) {
		return getFilesByStatus(FileGenerationStatus.STOPPED, pageable);
	}

	@Transactional(readOnly = true)
	public Page<FileGeneration> getFailedFileGenerations(Pageable pageable) {
		return getFilesByStatus(FileGenerationStatus.FAILED, pageable);
	}
}
