package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.silverlakesymmetri.cbs.fileGenerator.scheduler.BatchJobLauncher;
import com.silverlakesymmetri.cbs.fileGenerator.scheduler.FileGenerationScheduler;
import com.silverlakesymmetri.cbs.fileGenerator.scheduler.MaintenanceScheduler;
import org.quartz.*;
import org.quartz.spi.JobFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import javax.sql.DataSource;
import java.util.Properties;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.*;

@Configuration
public class QuartzConfig {
	private final DataSource dataSource;
	private final ConfigurableEnvironment env;

	@Autowired
	public QuartzConfig(DataSource dataSource, ConfigurableEnvironment env) {
		this.dataSource = dataSource;
		this.env = env;
	}

	@Bean
	public JobFactory jobFactory(ApplicationContext applicationContext) {
		AutowiringSpringBeanJobFactory jobFactory = new AutowiringSpringBeanJobFactory();
		jobFactory.setApplicationContext(applicationContext);
		return jobFactory;
	}

	@Bean
	public SchedulerFactoryBean schedulerFactoryBean(JobFactory jobFactory, Trigger[] triggers) {
		SchedulerFactoryBean factory = new SchedulerFactoryBean();
		factory.setJobFactory(jobFactory);

		// 1. Link to the main DataSource for JDBC JobStore
		factory.setDataSource(dataSource);

		// 2. Load the Quartz properties from application.properties
		factory.setQuartzProperties(quartzProperties());

		// Automatically picks up all defined Triggers (including the Maintenance one)
		factory.setTriggers(triggers);

		// CRITICAL: Allows updating Cron expressions in properties without DB errors
		factory.setOverwriteExistingJobs(true);
		factory.setAutoStartup(true);
		factory.setWaitForJobsToCompleteOnShutdown(true);
		return factory;
	}

	/**
	 * Helper to extract all "spring.quartz.properties.*" from the environment
	 * and strip the prefix so Quartz can understand them.
	 */
	private Properties quartzProperties() {
		Properties properties = new Properties();
		// Search through all property sources (application.properties, env variables, etc.)
		for (PropertySource<?> source : env.getPropertySources()) {
			if (source instanceof EnumerablePropertySource) {
				for (String key : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
					if (key.startsWith("spring.quartz.properties.")) {
						// Strip "spring.quartz.properties." and keep the rest
						// e.g., "org.quartz.jobStore.class"
						String quartzKey = key.replace("spring.quartz.properties.", "");
						properties.put(quartzKey, env.getProperty(key));
					}
				}
			}
		}
		return properties;
	}

	/**
	 * Generic Job Detail used for manual/ad-hoc triggers via REST API.
	 * It is stored durably without a trigger.
	 */
	@Bean(name = FILE_GEN_ADHOC_JOB)
	public JobDetail adHocBatchJobDetail() {
		return JobBuilder.newJob(BatchJobLauncher.class)
				.withIdentity(FILE_GEN_ADHOC_JOB, FILE_GEN_GROUP)
				.storeDurably() // Allows the job to exist without a trigger
				.build();
	}

	//==================================================================================================================
	// Maintenance (House keeping)
	//==================================================================================================================
	@Value("${maintenance.scheduler.cron:0 0 0 * * SUN}")
	private String maintenanceCron;

	@Bean
	public JobDetail maintenanceJobDetail() {
		return JobBuilder.newJob(MaintenanceScheduler.class)
				.withIdentity("maintenanceCleanupJob", "system-maintenance")
				.storeDurably()
				.build();
	}

	@Bean
	public Trigger maintenanceJobTrigger(@Qualifier("maintenanceJobDetail") JobDetail jobDetail) {
		return TriggerBuilder.newTrigger()
				.forJob(jobDetail)
				.withIdentity("maintenanceCleanupTrigger", "system-maintenance")
				.withSchedule(CronScheduleBuilder.cronSchedule(maintenanceCron)
						.withMisfireHandlingInstructionDoNothing())
				.build();
	}

	//==================================================================================================================
	// Poller for PENDING Batch File Generation Jobs
	//==================================================================================================================
	@Value("${batch.scheduler.cron:0 0/1 * * * ?}")
	private String pollerCron;

	@Bean
	public JobDetail fileGenPollJobDetail() {
		return JobBuilder.newJob(FileGenerationScheduler.class)
				.withIdentity(FILE_GEN_POLL_JOB, FILE_GEN_GROUP)
				.storeDurably()
				.build();
	}

	/**
	 * Define the Trigger for the Poller
	 */
	@Bean
	public Trigger fileGenPollTrigger(@Qualifier("fileGenPollJobDetail") JobDetail jobDetail) {
		return TriggerBuilder.newTrigger()
				.forJob(jobDetail)
				.withIdentity(FILE_GEN_TRIGGER_NAME, FILE_GEN_GROUP)
				.withSchedule(CronScheduleBuilder.cronSchedule(pollerCron)
						.withMisfireHandlingInstructionDoNothing())
				.build();
	}

	//==================================================================================================================
	// ORDER_INTERFACE
	//==================================================================================================================
	@Value("${ordersInterface.scheduler.cron:0 0 5 * * ?}")
	private String ordersInterfaceCron;

	@Bean
	public JobDetail ordersJobDetail() {
		JobDataMap jobDataMap = new JobDataMap();
		jobDataMap.put("interfaceType", ORDER_INTERFACE);

		return JobBuilder.newJob(BatchJobLauncher.class)
				.withIdentity(FILE_GEN_ORDERS_JOB, FILE_GEN_GROUP)
				.setJobData(jobDataMap)
				.storeDurably()
				.build();
	}

	/**
	 * Define the Cron Trigger for Orders
	 */
	@Bean
	public Trigger ordersJobTrigger(@Qualifier("ordersJobDetail") JobDetail jobDetail) {
		return TriggerBuilder.newTrigger()
				.forJob(jobDetail)
				.withIdentity(FILE_GEN_ORDERS_TRIGGER_NAME, FILE_GEN_GROUP)
				.withSchedule(CronScheduleBuilder.cronSchedule(ordersInterfaceCron)
						.withMisfireHandlingInstructionDoNothing())
				.build();
	}
}
