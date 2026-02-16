package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.silverlakesymmetri.cbs.fileGenerator.tasklets.BatchCleanupTasklet;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for system maintenance tasks.
 */
@Configuration
public class MaintenanceBatchConfig {
	private final JobBuilderFactory jobBuilderFactory;
	private final StepBuilderFactory stepBuilderFactory;
	private final BatchCleanupTasklet batchCleanupTasklet;

	@Autowired
	public MaintenanceBatchConfig(JobBuilderFactory jobBuilderFactory,
								  StepBuilderFactory stepBuilderFactory,
								  BatchCleanupTasklet batchCleanupTasklet) {
		this.jobBuilderFactory = jobBuilderFactory;
		this.stepBuilderFactory = stepBuilderFactory;
		this.batchCleanupTasklet = batchCleanupTasklet;
	}

	/**
	 * The Cleanup Job.
	 * This bean name "cleanupJob" will be visible in the BatchJobLauncher map.
	 */
	@Bean
	public Job cleanupJob() {
		return jobBuilderFactory.get("cleanupJob")
				.start(cleanupStep())
				.build();
	}

	@Bean
	public Step cleanupStep() {
		return stepBuilderFactory.get("cleanupStep")
				.tasklet(batchCleanupTasklet)
				.build();
	}
}
