package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.silverlakesymmetri.cbs.fileGenerator.scheduler.MaintenanceQuartzJob;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Trigger;
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
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration
public class QuartzConfiguration {
	private final DataSource dataSource;
	private final ConfigurableEnvironment env;
	@Value("${maintenance.scheduler.cron:0 0 0 * * SUN}")
	private String cronSchedule;

	@Autowired
	public QuartzConfiguration(DataSource dataSource, ConfigurableEnvironment env) {
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

		factory.setAutoStartup(true);
		factory.setWaitForJobsToCompleteOnShutdown(true);
		return factory;
	}

	@Bean
	public JobDetailFactoryBean maintenanceJobDetail() {
		JobDetailFactoryBean factory = new JobDetailFactoryBean();
		factory.setJobClass(MaintenanceQuartzJob.class);
		factory.setDurability(true);
		factory.setGroup("system-maintenance");
		factory.setName("maintenanceCleanupJob");
		return factory;
	}

	@Bean
	public CronTriggerFactoryBean maintenanceJobTrigger(@Qualifier("maintenanceJobDetail") JobDetail jobDetail) {
		CronTriggerFactoryBean factory = new CronTriggerFactoryBean();
		factory.setJobDetail(jobDetail);
		factory.setCronExpression(cronSchedule);
		factory.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
		factory.setGroup("system-maintenance");
		factory.setName("maintenanceCleanupTrigger");
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
}
