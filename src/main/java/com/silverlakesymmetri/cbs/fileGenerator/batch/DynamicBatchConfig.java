package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileFinalizationService;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Production-ready dynamic batch configuration for generic file generation.
 */
@Configuration
public class DynamicBatchConfig {
	private static final Logger logger = LoggerFactory.getLogger(DynamicBatchConfig.class);

	private final JobBuilderFactory jobBuilderFactory;
	private final StepBuilderFactory stepBuilderFactory;
	private final FileGenerationService fileGenerationService;
	private final FileFinalizationService fileFinalizationService;
	private final DynamicItemReader dynamicItemReader;
	private final DynamicItemProcessor dynamicItemProcessor;
	private final DynamicItemWriter dynamicItemWriter;

	@Autowired
	private BatchCleanupTasklet batchCleanupTasklet;

	@Value("${batch.chunk.size:1000}")
	private int chunkSize;

	public DynamicBatchConfig(
			JobBuilderFactory jobBuilderFactory,
			StepBuilderFactory stepBuilderFactory,
			FileGenerationService fileGenerationService,
			FileFinalizationService fileFinalizationService,
			DynamicItemReader dynamicItemReader,
			DynamicItemProcessor dynamicItemProcessor,
			DynamicItemWriter dynamicItemWriter
	) {
		this.jobBuilderFactory = jobBuilderFactory;
		this.stepBuilderFactory = stepBuilderFactory;
		this.fileGenerationService = fileGenerationService;
		this.fileFinalizationService = fileFinalizationService;
		this.dynamicItemReader = dynamicItemReader;
		this.dynamicItemProcessor = dynamicItemProcessor;
		this.dynamicItemWriter = dynamicItemWriter;
	}

	// Define the Job Listener as a Bean
	@Bean
	public DynamicJobExecutionListener jobExecutionListener() {
		return new DynamicJobExecutionListener(fileGenerationService, fileFinalizationService);
	}

	// Define the Step Listener as a Bean
	@Bean
	public DynamicStepExecutionListener stepExecutionListener() {
		return new DynamicStepExecutionListener(dynamicItemWriter, fileGenerationService);
	}

	@Bean
	public Job dynamicFileGenerationJob() {
		logger.info("Configuring dynamicFileGenerationJob with chunk size {}", chunkSize);
		return jobBuilderFactory.get("dynamicFileGenerationJob")
				.incrementer(new RunIdIncrementer())
				.listener(jobExecutionListener())
				.flow(dynamicFileGenerationStep())
				.end()
				.build();
	}

	@Bean
	public Step dynamicFileGenerationStep() {
		logger.info("Configuring dynamicFileGenerationStep");
		return stepBuilderFactory.get("dynamicFileGenerationStep")
				.<DynamicRecord, DynamicRecord>chunk(chunkSize)
				.reader(dynamicItemReader)
				.processor(dynamicItemProcessor)
				.writer(dynamicItemWriter)
				.listener(stepExecutionListener())
				.allowStartIfComplete(true) // allows restart with .part handling
				.build();
	}

	/**
	 * Define an Asynchronous JobLauncher.
	 * This replaces the default synchronous launcher.
	 */
	@Bean
	public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
		SimpleJobLauncher jobLauncher = new SimpleJobLauncher();
		jobLauncher.setJobRepository(jobRepository);
		// Assign the ThreadPoolTaskExecutor to handle jobs in background threads
		jobLauncher.setTaskExecutor(batchTaskExecutor());
		jobLauncher.afterPropertiesSet();
		return jobLauncher;
	}

	/**
	 * Define a ThreadPool specifically for Batch Jobs.
	 * This prevents the server from being overwhelmed by too many concurrent file generations.
	 */
	@Bean
	public TaskExecutor batchTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(5);      // Minimum 5 threads
		executor.setMaxPoolSize(10);      // Maximum 10 concurrent files
		executor.setQueueCapacity(25);    // 25 jobs can wait in queue
		executor.setThreadNamePrefix("Batch-Async-");
		executor.initialize();
		return executor;
	}

	@Bean
	public Step cleanupStep() {
		return stepBuilderFactory.get("cleanupStep")
				.tasklet(batchCleanupTasklet) // Use the injected bean
				.build();
	}

	@Bean
	public Job cleanupJob() {
		return jobBuilderFactory.get("cleanupJob")
				.start(cleanupStep())
				.build();
	}
}
