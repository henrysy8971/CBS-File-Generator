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

	@Recover
	public void recover(Exception e, String jobId) {
		logger.error("CRITICAL: Failed after retries for jobId={}", jobId, e);
		// Here you could send an alert to an IT support Slack channel or email
	}

	private void transitionStatus(String jobId, FileGenerationStatus nextStatus, String errorMessage) {
		FileGenerationStatus currentStatus = repository.findStatusByJobId(jobId)
				.orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

		// Prevent updates to terminal jobs
		if (currentStatus.isTerminal()) {
			throw new IllegalStateException(
					"Cannot update terminal job. jobId=" + jobId +
							", status=" + currentStatus
			);
		}

		// Enforce lifecycle rules
		if (!currentStatus.canTransitionTo(nextStatus)) {
			throw new IllegalStateException(
					"Invalid status transition: " +
							currentStatus + " -> " + nextStatus +
							" for jobId=" + jobId
			);
		}

		Timestamp completedDate = nextStatus.isTerminal() ? now() : null;
		int updated = repository.updateStatus(jobId, nextStatus.name(), errorMessage, completedDate);

		if (updated == 0) {
			throw new IllegalStateException("Job not found: " + jobId);
		}

		logger.info("File generation status transitioned: jobId={}, {} -> {}", jobId, currentStatus, nextStatus);
	}

	/* ===================== METRICS ===================== */

	@Transactional
	public void updateFileMetrics(String jobId, long processed, long skipped, long invalid) {

		FileGenerationStatus status = repository.findStatusByJobId(jobId)
				.orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

		if (status.isTerminal()) {
			throw new IllegalStateException(
					"Cannot update metrics for terminal job: " + jobId
			);
		}

		repository.updateMetrics(jobId, processed, skipped, invalid);

		logger.info(
				"Metrics updated: jobId={}, processed={}, skipped={}, invalid={}",
				jobId, processed, skipped, invalid
		);
	}

	/* ===================== Queries ===================== */

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

	/* ===================== Helpers ===================== */

	private Timestamp now() {
		return new Timestamp(System.currentTimeMillis());
	}
}
