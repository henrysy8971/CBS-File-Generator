package com.silverlakesymmetri.cbs.fileGenerator.scheduler;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Cluster-safe Quartz Job that triggers the Spring Batch Maintenance Job.
 * <p>
 * {@code @DisallowConcurrentExecution} prevents the same job from starting if the
 * previous week's cleanup is still running.
 */
@Component
@DisallowConcurrentExecution
public class MaintenanceQuartzJob implements org.quartz.Job {
	private static final Logger logger = LoggerFactory.getLogger(MaintenanceQuartzJob.class);

	private final JobLauncher jobLauncher;
	private final Job cleanupJob;

	public MaintenanceQuartzJob(JobLauncher jobLauncher, @Qualifier("cleanupJob") Job cleanupJob) {
		this.jobLauncher = jobLauncher;
		this.cleanupJob = cleanupJob;
	}

	@Override
	public void execute(JobExecutionContext context) throws JobExecutionException {
		try {
			JobParameters params = new JobParametersBuilder()
					.addLong("time", System.currentTimeMillis())
					.toJobParameters();

			logger.info("Quartz triggering cluster-safe Maintenance Cleanup Job...");
			jobLauncher.run(cleanupJob, params);
		} catch (Exception e) {
			logger.error("Maintenance Batch Job failed to launch", e);
			throw new JobExecutionException(e);
		}
	}
}
