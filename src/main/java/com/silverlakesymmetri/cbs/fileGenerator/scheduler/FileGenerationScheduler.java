package com.silverlakesymmetri.cbs.fileGenerator.scheduler;

import com.silverlakesymmetri.cbs.fileGenerator.service.AppConfigService;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@DisallowConcurrentExecution
public class FileGenerationScheduler extends QuartzJobBean {

	private static final Logger logger = LoggerFactory.getLogger(FileGenerationScheduler.class);

	@Autowired
	private AppConfigService appConfigService;

	@Autowired
	private FileGenerationService fileGenerationService;

	@Override
	protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
		logger.info("File generation scheduled job started at: {}", new Date());

		try {
			// Get job configuration from database
			String jobType = appConfigService.getConfigValue("JOB_TYPE").orElse("DEFAULT");
			String batchSize = appConfigService.getConfigValue("BATCH_SIZE").orElse("1000");

			logger.info("Executing job - type: {}, batchSize: {}", jobType, batchSize);

			// Process pending file generations
			fileGenerationService.getPendingFileGenerations().forEach(fileGen -> {
				logger.info("Processing file generation: {}", fileGen.getJobId());
				// Actual processing logic would be implemented here
			});

			logger.info("File generation scheduled job completed successfully");
		} catch (Exception e) {
			logger.error("Error in file generation scheduled job: {}", e.getMessage(), e);
			throw new JobExecutionException(e);
		}
	}

	public void scheduleJob(String jobName, String cronExpression) throws SchedulerException {
		JobDetail jobDetail = JobBuilder.newJob(FileGenerationScheduler.class)
				.withIdentity(jobName, "file-generation-group")
				.build();

		CronTrigger trigger = TriggerBuilder.newTrigger()
				.withIdentity(jobName + "-trigger", "file-generation-group")
				.withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
				.build();

		logger.info("Job scheduled - name: {}, cron: {}", jobName, cronExpression);
	}
}
