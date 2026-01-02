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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.stream.Stream;

@Component
public class BatchCleanupTasklet implements Tasklet {
	private static final Logger logger = LoggerFactory.getLogger(BatchCleanupTasklet.class);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Value("${batch.cleanup.days:30}")
	private int daysToKeep;

	@Value("${file.generation.output-directory}")
	private String storageDirectory;

	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);
		Timestamp cutoffTimestamp = Timestamp.valueOf(cutoffDate);

		logger.info("Starting Batch maintenance. Removing data older than: {}", cutoffDate);

		// 1. Database Metadata Cleanup
		cleanupDatabaseMetadata(cutoffTimestamp);

		// 2. Orphaned File System Cleanup
		cleanupStaleFiles(cutoffDate);

		return RepeatStatus.FINISHED;
	}

	private void cleanupStaleFiles(LocalDateTime cutoffDate) {
		Path rootPath = Paths.get(storageDirectory);
		if (!Files.exists(rootPath)) {
			logger.warn("Storage directory does not exist, skipping file cleanup: {}", storageDirectory);
			return;
		}

		Instant cutoffInstant = cutoffDate.atZone(ZoneId.systemDefault()).toInstant();
		logger.info("Scanning for stale .part files in: {}", storageDirectory);

		try (Stream<Path> paths = Files.walk(rootPath)) {
			paths.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".part"))
					.forEach(path -> {
						try {
							BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
							if (attrs.lastModifiedTime().toInstant().isBefore(cutoffInstant)) {
								Files.delete(path);
								logger.info("Deleted stale part file: {}", path.getFileName());
							}
						} catch (IOException e) {
							logger.error("Failed to process file for cleanup: {}", path, e);
						}
					});
		} catch (IOException e) {
			logger.error("Error walking the storage directory: {}", storageDirectory, e);
		}
	}

	private void cleanupDatabaseMetadata(Timestamp cutoffTimestamp) {
		try {
			// 1. Delete Step Execution Contexts
			int rows1 = jdbcTemplate.update(
					"DELETE FROM BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID IN " +
							"(SELECT STEP_EXECUTION_ID FROM BATCH_STEP_EXECUTION WHERE START_TIME < ?)", cutoffTimestamp);

			// 2. Delete Step Executions
			int rows2 = jdbcTemplate.update(
					"DELETE FROM BATCH_STEP_EXECUTION WHERE START_TIME < ?", cutoffTimestamp);

			// 3. Delete Job Execution Contexts
			int rows3 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID IN " +
							"(SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE START_TIME < ?)", cutoffTimestamp);

			// 4. Delete Job Execution Params
			int rows4 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID IN " +
							"(SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE START_TIME < ?)", cutoffTimestamp);

			// 5. Delete Job Executions
			int rows5 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_EXECUTION WHERE START_TIME < ?", cutoffTimestamp);

			// 6. Delete Job Instances
			int rows6 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID NOT IN " +
							"(SELECT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION)");

			logger.info("DB Cleanup complete. Summary: {} StepContexts, {} Steps, {} JobContexts, {} Params, {} JobExecs, {} JobInstances deleted.",
					rows1, rows2, rows3, rows4, rows5, rows6);

		} catch (Exception e) {
			logger.error("Error occurred during database metadata cleanup", e);
		}
	}
}