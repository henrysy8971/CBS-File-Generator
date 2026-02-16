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
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * Cluster-safe Quartz Job that triggers the Spring Batch Maintenance Job.
 * <p>
 * {@code @DisallowConcurrentExecution} prevents the same job from starting if the
 * previous week's cleanup is still running.
 */
@Component
@DisallowConcurrentExecution
public class MaintenanceScheduler extends QuartzJobBean {
	private static final Logger logger = LoggerFactory.getLogger(MaintenanceScheduler.class);

	private final JobLauncher jobLauncher;
	private final Job cleanupJob;

	public MaintenanceScheduler(JobLauncher jobLauncher, @Qualifier("cleanupJob") Job cleanupJob) {
		this.jobLauncher = jobLauncher;
		this.cleanupJob = cleanupJob;
	}

	@Override
	protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
		try {
			// Unique parameter ensures Spring Batch runs a new instance every time
			JobParameters params = new JobParametersBuilder()
					.addLong("time", System.currentTimeMillis())
					.addString("type", "MAINTENANCE")
					.toJobParameters();

			logger.info("Quartz triggering Maintenance Job...");

			// Note: This blocks the Quartz thread until the batch job finishes.
			// For weekly maintenance, this is acceptable.
			jobLauncher.run(cleanupJob, params);

		} catch (Exception e) {
			logger.error("Maintenance Batch Job failed", e);
			throw new JobExecutionException(e);
		}
	}
}
