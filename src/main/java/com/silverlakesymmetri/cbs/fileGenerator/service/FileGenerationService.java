package com.silverlakesymmetri.cbs.fileGenerator.service;

import com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationStatus;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGenerationAudit;
import com.silverlakesymmetri.cbs.fileGenerator.exception.ConflictException;
import com.silverlakesymmetri.cbs.fileGenerator.exception.LifecycleException;
import com.silverlakesymmetri.cbs.fileGenerator.repository.FileGenerationAuditRepository;
import com.silverlakesymmetri.cbs.fileGenerator.repository.FileGenerationRepository;
import com.silverlakesymmetri.cbs.fileGenerator.retry.DbRetryable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Recover;
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
	private final FileGenerationAuditRepository fileGenerationAuditRepository;

	@Autowired
	public FileGenerationService(FileGenerationRepository fileGenerationRepository,
								 FileGenerationAuditRepository fileGenerationAuditRepository) {
		this.fileGenerationRepository = fileGenerationRepository;
		this.fileGenerationAuditRepository = fileGenerationAuditRepository;
	}

	// ==================== Create ====================
	@Transactional
	public FileGeneration createFileGeneration(
			String fileName,
			String filePath,
			String createdBy,
			String interfaceType,
			String idempotencyKey) {

		// 1. Idempotency Check
		if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
			Optional<FileGeneration> existing = fileGenerationRepository.findByIdempotencyKey(idempotencyKey);
			if (existing.isPresent()) {
				logger.info("Idempotent request: returning existing job {} for key {}",
						existing.get().getJobId(), idempotencyKey);
				return existing.get();
			}
		}

		validateNonEmpty(fileName, "fileName");
		validateNonEmpty(filePath, "filePath");
		validateNonEmpty(createdBy, "createdBy");
		validateNonEmpty(interfaceType, "interfaceType");

		try {
			FileGeneration fg = new FileGeneration();
			fg.setJobId(UUID.randomUUID().toString());
			fg.setIdempotencyKey(idempotencyKey);
			fg.setFileName(fileName);
			fg.setFilePath(filePath);
			fg.setStatus(FileGenerationStatus.PENDING);
			fg.setCreatedBy(createdBy);
			fg.setInterfaceType(interfaceType);
			fg.setCreatedDate(now());

			FileGeneration saved = fileGenerationRepository.save(fg);

			logger.info("File generation record created: jobId={}, fileName={}, interfaceType={}",
					saved.getJobId(), fileName, interfaceType);

			// STRUCTURED LOGGING for Creation
			logger.info("[JOB_CREATED] jobId={} interface={} fileName={} user={}",
					saved.getJobId(),
					interfaceType,
					fileName,
					createdBy);

			return saved;
		} catch (DataIntegrityViolationException e) {
			// Handle race condition where two threads inserted same key at exact same time
			if (idempotencyKey != null) {
				return fileGenerationRepository.findByIdempotencyKey(idempotencyKey)
						.orElseThrow(() -> new ConflictException("Concurrent creation conflict", e));
			}
			// DB-level enforcement: unique active job per interface
			throw new ConflictException(
					"A job for this interface is already running", e
			);
		}
	}

	/* ===================== STATUS ===================== */
	@DbRetryable
	@Transactional
	public void markQueued(String jobId) {
		validateNonEmpty(jobId, "jobId");
		// This transitions PENDING -> QUEUED
		transitionStatus(jobId, FileGenerationStatus.QUEUED, null);
	}

	@DbRetryable
	@Transactional
	public void markProcessing(String jobId) {
		validateNonEmpty(jobId, "jobId");
		transitionStatus(jobId, FileGenerationStatus.PROCESSING, null);
	}

	@DbRetryable
	@Transactional
	public void markCompleted(String jobId) {
		validateNonEmpty(jobId, "jobId");
		transitionStatus(jobId, FileGenerationStatus.COMPLETED, null);
	}

	@DbRetryable
	@Transactional
	public void markFailed(String jobId, String errorMessage) {
		validateNonEmpty(jobId, "jobId");
		if (errorMessage == null) errorMessage = "UNKNOWN";
		transitionStatus(jobId, FileGenerationStatus.FAILED, errorMessage);
		logger.warn("File generation failed: jobId={}, error={}", jobId, errorMessage);
	}

	@Transactional(readOnly = true)
	public Page<FileGeneration> getFilesByStatus(FileGenerationStatus status, Pageable pageable) {
		return fileGenerationRepository.findAllByStatus(status, pageable);
	}

	@Recover
	@Transactional
	public void recoverOptimisticLock(
			ObjectOptimisticLockingFailureException e, String jobId) {
		logger.error("[RECOVER][OPTIMISTIC_LOCK] Retries exhausted for jobId={}", jobId, e);

		try {
			// Attempt to force the status to 'FAILED', so it doesn't get stuck
			transitionStatus(jobId, FileGenerationStatus.FAILED, "OPTIMISTIC_LOCK_EXHAUSTED");
		} catch (Exception ex) {
			// If even the recovery fails (e.g., DB down), just log it.
			logger.error("[RECOVER][SECONDARY] Failed to mark job as FAILED. Manual intervention required for jobId={}", jobId, ex);
		}
	}

	@Recover
	@Transactional
	public void recoverTransient(
			TransientDataAccessException e, String jobId) {

		logger.error("[RECOVER][TRANSIENT_DB] jobId={}", jobId, e);
		try {
			transitionStatus(jobId, FileGenerationStatus.FAILED, "DB_CONNECTION_ERROR");
		} catch (Exception ex) {
			logger.error("[RECOVER][SECONDARY] Failed to mark job as FAILED", ex);
		}
	}

	@Recover
	@Transactional
	public void recoverFailed(
			ObjectOptimisticLockingFailureException e,
			String jobId,
			String errorMessage) {

		logger.error("[RECOVER][FAILED][OPTIMISTIC_LOCK] jobId={}, error={}",
				jobId, errorMessage, e);

		try {
			transitionStatus(jobId, FileGenerationStatus.FAILED, "RETRY_EXHAUSTED");
		} catch (Exception ex) {
			logger.error("[RECOVER][FAILED][SECONDARY] jobId={}", jobId, ex);
		}
	}

	@Recover
	public void recoverFailed(
			TransientDataAccessException e,
			String jobId,
			String errorMessage) {

		logger.error("[RECOVER][FAILED][TRANSIENT_DB] jobId={}, error={}",
				jobId, errorMessage, e);
	}

	private void transitionStatus(String jobId, FileGenerationStatus nextStatus, String errorMessage) {
		FileGeneration fileGeneration = fileGenerationRepository.findByJobId(jobId)
				.orElseThrow(() -> new LifecycleException("Job not found: " + jobId));

		// 1. Fetch current status for the log
		FileGenerationStatus currentStatus = fileGeneration.getStatus();

		// Idempotency Check
		if (currentStatus == nextStatus) {
			logger.info("Job {} is already in status {}. No transition needed.", jobId, nextStatus);
			return;
		}

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
		logger.info("[STATUS_CHANGE] jobId={} prevStatus={} nextStatus={} error={}",
				jobId,
				currentStatus,
				nextStatus,
				errorMessage != null ? errorMessage : "NONE");

		fileGenerationAuditRepository.save(new FileGenerationAudit(jobId,
				currentStatus.name(),
				nextStatus.name(),
				fileGeneration.getCreatedBy(),
				errorMessage != null ? errorMessage : "NONE"
		));
	}

	/* ===================== METRICS ===================== */
	@DbRetryable
	@Transactional
	public void updateFileMetrics(String jobId, long processed, long skipped, long invalid) {
		validateNonEmpty(jobId, "jobId");

		FileGenerationStatus status = fileGenerationRepository.findStatusByJobId(jobId)
				.orElseThrow(() -> new LifecycleException("Job not found: " + jobId));

		if (status.isTerminal()) {
			throw new LifecycleException("Cannot update metrics for terminal job: " + jobId);
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
		validateNonEmpty(jobId, "jobId");
		return fileGenerationRepository.findByJobId(jobId);
	}

	@Transactional(readOnly = true)
	public List<FileGeneration> getPendingFileGenerations() {
		return fileGenerationRepository.findByStatus(FileGenerationStatus.PENDING);
	}

	@Transactional(readOnly = true)
	public boolean hasRunningJob(String interfaceType) {
		return fileGenerationRepository.existsByInterfaceTypeAndStatus(
				interfaceType,
				FileGenerationStatus.PROCESSING
		);
	}

	@Transactional(readOnly = true)
	public Page<FileGeneration> getAllFiles(Pageable pageable) {
		return fileGenerationRepository.findAll(pageable);
	}

	/* ===================== Helpers ===================== */
	private Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}

	public long getPendingCount() {
		// Assuming "PENDING" or "IN_PROGRESS" are your status strings
		return fileGenerationRepository.countByStatus(FileGenerationStatus.PENDING);
	}

	private void validateNonEmpty(String value, String fieldName) {
		if (value == null || value.trim().isEmpty()) {
			throw new IllegalArgumentException(fieldName + " cannot be null or empty");
		}
	}
}
