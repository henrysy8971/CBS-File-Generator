package com.silverlakesymmetri.cbs.fileGenerator.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MaintenanceScheduler {
	private static final Logger logger = LoggerFactory.getLogger(MaintenanceScheduler.class);

	@Autowired
	private JobLauncher jobLauncher;

	@Autowired
	private Job cleanupJob; // You would define this in DynamicBatchConfig

	@Scheduled(cron = "0 0 0 * * SUN") // Every Sunday at Midnight
	public void runCleanup() {
		try {
			JobParameters params = new JobParametersBuilder()
					.addLong("time", System.currentTimeMillis())
					.toJobParameters();
			logger.info("Starting scheduled Batch Metadata Cleanup...");
			jobLauncher.run(cleanupJob, params);
		} catch (Exception e) {
			logger.error("Maintenance Cleanup Job failed", e);
		}
	}
}