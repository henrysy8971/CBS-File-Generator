package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.silverlakesymmetri.cbs.fileGenerator.scheduler.FileGenerationScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SchedulerStartupRunner implements CommandLineRunner {

	private static final Logger logger = LoggerFactory.getLogger(SchedulerStartupRunner.class);

	@Autowired
	private FileGenerationScheduler fileGenerationScheduler;

	@Value("${batch.scheduler.cron:0 0/5 * * * ?}") // Default to every 5 minutes
	private String cronExpression;

	@Override
	public void run(String... args) throws Exception {
		logger.info("Initializing Quartz Scheduler with cron: {}", cronExpression);
		try {
			fileGenerationScheduler.createRecurringSchedule(cronExpression);
		} catch (Exception e) {
			logger.error("Failed to initialize recurring schedule", e);
		}
	}
}
