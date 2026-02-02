package com.silverlakesymmetri.cbs.fileGenerator.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Component
public class BatchCleanupTasklet implements Tasklet {
	private static final Logger logger = LoggerFactory.getLogger(BatchCleanupTasklet.class);
	private final JdbcTemplate jdbcTemplate;

	@Value("${file.generation.max-file-age-in-days:30}")
	private int daysToKeep;

	@Value("${file.generation.output-directory}")
	private String storageDirectory;

	@Autowired
	public BatchCleanupTasklet(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		Instant now = Instant.now();
		Instant cutoffInstant = now
				.minus(daysToKeep, ChronoUnit.DAYS)
				.minus(1, ChronoUnit.HOURS);
		Timestamp cutoffTimestamp = Timestamp.from(cutoffInstant);

		logger.info("Starting Batch maintenance. Current Time: {}, Cutoff: {}", now, cutoffInstant);

		// 1. Database Metadata Cleanup
		cleanupDatabaseMetadata(cutoffTimestamp);

		// 2. Orphaned File System Cleanup
		cleanupStaleFiles(cutoffInstant);

		return RepeatStatus.FINISHED;
	}

	private void cleanupStaleFiles(Instant cutoffInstant) {
		String locDir = storageDirectory == null ? "" : storageDirectory.trim();
		if (locDir.isEmpty()) {
			logger.warn("file.generation.output-directory value in application properties file is empty.");
			return;
		}

		Path rootPath;
		try {
			rootPath = Paths.get(locDir).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			logger.error("Invalid storage directory path: {}", locDir, e);
			return;
		}

		if (!Files.exists(rootPath)) {
			logger.warn("Storage directory does not exist, skipping file cleanup: {}", locDir);
			return;
		}

		logger.info("Scanning for stale files modified before: {}", cutoffInstant);
		AtomicInteger deletedCount = new AtomicInteger();

		try (Stream<Path> paths = Files.walk(rootPath, 5)) {
			paths.forEach(path -> {
				try {
					BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
							LinkOption.NOFOLLOW_LINKS);
					if (!attrs.isRegularFile()) return;

					Instant fileLastModified = attrs.lastModifiedTime().toInstant();

					if (fileLastModified.isBefore(cutoffInstant)) {
						try {
							Files.deleteIfExists(path);
							deletedCount.incrementAndGet();
							logger.info("Deleted stale file: {} (Modified: {})", path, fileLastModified);
						} catch (IOException e) {
							logger.warn("Failed to delete stale file (may be in use): {}", path, e);
						}
					}
				} catch (IOException e) {
					logger.error("Failed to process file for cleanup: {}", path, e);
				}
			});
		} catch (IOException e) {
			logger.error("Error walking the storage directory: {}", locDir, e);
		}

		logger.info("Cleanup completed. {} stale files deleted.", deletedCount.get());
	}

	@Transactional
	public void cleanupDatabaseMetadata(Timestamp cutoffTimestamp) {
		try {
			// 1. Delete Step Execution Contexts
			int rows1 = jdbcTemplate.update(
					"DELETE FROM BATCH_STEP_EXECUTION_CONTEXT" +
							" WHERE STEP_EXECUTION_ID IN (" +
							"    SELECT STEP_EXECUTION_ID FROM BATCH_STEP_EXECUTION" +
							"    WHERE START_TIME < ?" +
							"    AND STATUS NOT IN ('STARTED', 'STARTING', 'STOPPING'))"
					, cutoffTimestamp);

			// 2. Delete Step Executions
			int rows2 = jdbcTemplate.update(
					"DELETE FROM BATCH_STEP_EXECUTION" +
							" WHERE START_TIME < ?" +
							" AND STATUS NOT IN ('STARTED', 'STARTING', 'STOPPING')"
					, cutoffTimestamp);

			// 3. Delete Job Execution Contexts
			int rows3 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_EXECUTION_CONTEXT" +
							" WHERE JOB_EXECUTION_ID IN (" +
							"    SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION" +
							"    WHERE START_TIME < ?" +
							"    AND STATUS NOT IN ('STARTED', 'STARTING', 'STOPPING'))"
					, cutoffTimestamp);

			// 4. Delete Job Execution Params
			int rows4 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_EXECUTION_PARAMS" +
							" WHERE JOB_EXECUTION_ID IN (" +
							"    SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION" +
							"    WHERE START_TIME < ?" +
							"    AND STATUS NOT IN ('STARTED', 'STARTING', 'STOPPING'))"
					, cutoffTimestamp);

			// 5. Delete Job Executions
			int rows5 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_EXECUTION" +
							" WHERE START_TIME < ?" +
							" AND STATUS NOT IN ('STARTED', 'STARTING', 'STOPPING')"
					, cutoffTimestamp);

			// 6. Delete Job Instances (The parent of Executions)
			// Only delete instances that no longer have any executions
			jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_INSTANCE i WHERE NOT EXISTS (" +
							"SELECT 1 FROM BATCH_JOB_EXECUTION e WHERE e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID)"
			);

			// 7. Application-specific FileGeneration table cleanup
			// Optional: Keep this if you want to purge history as well
			int appRows = jdbcTemplate.update(
					"DELETE FROM IF_FILE_GENERATION WHERE CREATED_DATE < ? AND STATUS IN ('COMPLETED', 'FAILED')"
					, cutoffTimestamp
			);

			logger.info("DB Cleanup complete. Summary: {} StepContexts, {} Steps, {} JobContexts, {} Params, {} JobExecs, {} AppRows deleted.",
					rows1, rows2, rows3, rows4, rows5, appRows);

		} catch (Exception e) {
			logger.error("Error occurred during database metadata cleanup", e);
			throw e;
		}
	}
}
