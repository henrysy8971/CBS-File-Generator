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

	@Autowired
	private FileGenerationService fileGenerationService;

	@Autowired
	private BatchJobLauncher batchJobLauncher;

	@Autowired
	private Scheduler scheduler; // Injected to actually perform the scheduling

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
		JobDetail jobDetail = JobBuilder.newJob(FileGenerationScheduler.class)
				.withIdentity("fileGenPollJob", "file-generation-group")
				.storeDurably() // Important for QuartzJobBean
				.build();

		Trigger trigger = TriggerBuilder.newTrigger()
				.withIdentity("fileGenPollTrigger", "file-generation-group")
				.withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
						.withMisfireHandlingInstructionDoNothing()) // Don't pile up jobs if server was down
				.build();

		if (!scheduler.checkExists(jobDetail.getKey())) {
			scheduler.scheduleJob(jobDetail, trigger);
			logger.info("Recurring File Generation Poller scheduled with cron: {}", cronExpression);
		}
	}
}
