package com.silverlakesymmetri.cbs.fileGenerator.scheduler;

import com.silverlakesymmetri.cbs.fileGenerator.service.BatchJobLauncher;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

@Component
@DisallowConcurrentExecution
public class FileGenerationScheduler extends QuartzJobBean {
	private static final Logger logger = LoggerFactory.getLogger(FileGenerationScheduler.class);
	private final FileGenerationService fileGenerationService;
	private final BatchJobLauncher batchJobLauncher;
	private final Scheduler scheduler; // Injected to actually perform the scheduling

	@Autowired
	public FileGenerationScheduler(FileGenerationService fileGenerationService, BatchJobLauncher batchJobLauncher, Scheduler scheduler) {
		this.fileGenerationService = fileGenerationService;
		this.batchJobLauncher = batchJobLauncher;
		this.scheduler = scheduler;
	}

	@Override
	protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
		logger.info("Quartz checking for pending file generation requests...");

		try {
			// 1. Fetch pending requests from DB
			fileGenerationService.getPendingFileGenerations().forEach(fileGen -> {
				try {
					logger.info("Scheduling Batch Job for request: {}", fileGen.getJobId());

					// 2. Hand off to the Batch Launcher
					// This triggers the full Spring Batch lifecycle (Reader -> Processor -> Writer -> Listener)
					batchJobLauncher.launchFileGenerationJob(
							fileGen.getJobId(),
							fileGen.getInterfaceType()
					);
				} catch (Exception e) {
					logger.error("Failed to hand off JobId {} to Batch Launcher", fileGen.getJobId(), e);
				}
			});
		} catch (Exception e) {
			throw new JobExecutionException("Failed to retrieve pending jobs from database", e);
		}
	}

	/**
	 * Call this from a Startup Runner or API to initialize the recurring trigger
	 */
	public void createRecurringSchedule(String cronExpression) throws SchedulerException {
		JobKey jobKey = JobKey.jobKey("fileGenPollJob", "file-generation-group");
		TriggerKey triggerKey = TriggerKey.triggerKey("fileGenPollTrigger", "file-generation-group");

		JobDetail jobDetail = JobBuilder.newJob(FileGenerationScheduler.class)
				.withIdentity(jobKey)
				.storeDurably()
				.build();

		Trigger trigger = TriggerBuilder.newTrigger()
				.withIdentity(triggerKey)
				.withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
						.withMisfireHandlingInstructionDoNothing()) // Don't pile up jobs if server was down
				.build();

		if (!scheduler.checkExists(jobDetail.getKey())) {
			scheduler.scheduleJob(jobDetail, trigger);
			logger.info("Recurring File Generation Poller scheduled with cron: {}", cronExpression);
		} else {
			// This ensures that if you change the CRON in application.properties,
			// the DB is updated on restart.
			scheduler.rescheduleJob(triggerKey, trigger);
			logger.info("Recurring Poller schedule updated to: {}", cronExpression);
		}
	}
}
