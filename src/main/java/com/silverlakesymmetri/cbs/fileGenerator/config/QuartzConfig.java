package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.silverlakesymmetri.cbs.fileGenerator.scheduler.FileGenerationScheduler;
import org.quartz.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzConfig {

	/**
	 * Job detail bean for file generation scheduler
	 */
	@Bean
	public JobDetail fileGenerationJobDetail() {
		return JobBuilder.newJob(FileGenerationScheduler.class)
				.withIdentity("fileGenerationJob", "file-generation-group")
				.storeDurably()
				.build();
	}

	/**
	 * Trigger for daily file generation at 2 AM
	 */
	@Bean
	public Trigger dailyFileGenerationTrigger(JobDetail fileGenerationJobDetail) {
		return TriggerBuilder.newTrigger()
				.forJob(fileGenerationJobDetail)
				.withIdentity("dailyFileGenerationTrigger", "file-generation-group")
				.withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(2, 0))
				.build();
	}

	/**
	 * Trigger for hourly file generation (optional)
	 */
	@Bean
	public Trigger hourlyFileGenerationTrigger(JobDetail fileGenerationJobDetail) {
		return TriggerBuilder.newTrigger()
				.forJob(fileGenerationJobDetail)
				.withIdentity("hourlyFileGenerationTrigger", "file-generation-group")
				.withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?"))
				.build();
	}

	/**
	 * Trigger for every 30 minutes
	 */
	@Bean
	public Trigger every30MinutesTrigger(JobDetail fileGenerationJobDetail) {
		return TriggerBuilder.newTrigger()
				.forJob(fileGenerationJobDetail)
				.withIdentity("every30MinutesTrigger", "file-generation-group")
				.withSchedule(CronScheduleBuilder.cronSchedule("0 */30 * * * ?"))
				.build();
	}
}
