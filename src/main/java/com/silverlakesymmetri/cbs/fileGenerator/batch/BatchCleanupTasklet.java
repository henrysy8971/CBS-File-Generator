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

import java.time.LocalDateTime;
import java.sql.Timestamp;

@Component
public class BatchCleanupTasklet implements Tasklet {
	private static final Logger logger = LoggerFactory.getLogger(BatchCleanupTasklet.class);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Value("${batch.cleanup.days:30}")
	private int daysToKeep;

	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		// Calculate the cutoff date
		LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);
		Timestamp cutoffTimestamp = Timestamp.valueOf(cutoffDate);

		logger.info("Starting Batch metadata cleanup. Removing records older than: {}", cutoffDate);

		try {
			// 1. Delete Step Execution Contexts (Child of Step Execution)
			int rows1 = jdbcTemplate.update(
					"DELETE FROM BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID IN " +
							"(SELECT STEP_EXECUTION_ID FROM BATCH_STEP_EXECUTION WHERE START_TIME < ?)", cutoffTimestamp);

			// 2. Delete Step Executions (Child of Job Execution)
			int rows2 = jdbcTemplate.update(
					"DELETE FROM BATCH_STEP_EXECUTION WHERE START_TIME < ?", cutoffTimestamp);

			// 3. Delete Job Execution Contexts (Child of Job Execution)
			int rows3 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID IN " +
							"(SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE START_TIME < ?)", cutoffTimestamp);

			// 4. Delete Job Execution Params (Child of Job Execution)
			int rows4 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID IN " +
							"(SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE START_TIME < ?)", cutoffTimestamp);

			// 5. Delete Job Executions (Child of Job Instance)
			int rows5 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_EXECUTION WHERE START_TIME < ?", cutoffTimestamp);

			// 6. Delete Job Instances (Parent-most table)
			// We only delete instances that no longer have any executions
			int rows6 = jdbcTemplate.update(
					"DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID NOT IN " +
							"(SELECT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION)");

			logger.info("Cleanup complete. Summary: {} StepContexts, {} Steps, {} JobContexts, {} Params, {} JobExecs, {} JobInstances deleted.",
					rows1, rows2, rows3, rows4, rows5, rows6);

		} catch (Exception e) {
			logger.error("Error occurred during Batch metadata cleanup", e);
			throw e; // Fail the step, so it can be retried or logged as failed
		}

		return RepeatStatus.FINISHED;
	}
}