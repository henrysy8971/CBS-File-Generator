package com.silverlakesymmetri.cbs.fileGenerator.batch.custom;

import com.silverlakesymmetri.cbs.fileGenerator.batch.BatchCleanupTasklet;
import com.silverlakesymmetri.cbs.fileGenerator.batch.DynamicJobExecutionListener;
import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
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
 * Batch configuration for ORDER_INTERFACE file generation.
 * Specialized configuration for Order processing with eager loading of line items.
 */
@Configuration
public class OrderBatchConfig {
	private static final Logger logger = LoggerFactory.getLogger(OrderBatchConfig.class);

	private final JobBuilderFactory jobBuilderFactory;
	private final StepBuilderFactory stepBuilderFactory;
	private final FileGenerationService fileGenerationService;
	private final FileFinalizationService fileFinalizationService;
	private final OrderItemReader orderItemReader;
	private final OrderItemProcessor orderItemProcessor;
	private final OrderItemWriter orderItemWriter;

	@Autowired
	private BatchCleanupTasklet batchCleanupTasklet;

	@Value("${batch.chunk.size:1000}")
	private int chunkSize;

	public OrderBatchConfig(JobBuilderFactory jobBuilderFactory,
							StepBuilderFactory stepBuilderFactory,
							FileGenerationService fileGenerationService,
							FileFinalizationService fileFinalizationService,
							OrderItemReader orderItemReader,
							OrderItemProcessor orderItemProcessor,
							OrderItemWriter orderItemWriter) {
		this.jobBuilderFactory = jobBuilderFactory;
		this.stepBuilderFactory = stepBuilderFactory;
		this.fileGenerationService = fileGenerationService;
		this.fileFinalizationService = fileFinalizationService;
		this.orderItemReader = orderItemReader;
		this.orderItemProcessor = orderItemProcessor;
		this.orderItemWriter = orderItemWriter;
	}

	// Define the Job Listener as a Bean
	@Bean
	public DynamicJobExecutionListener jobExecutionListener() {
		return new DynamicJobExecutionListener(fileGenerationService, fileFinalizationService);
	}

	@Bean
	public OrderStepExecutionListener stepExecutionListener() {
		return new OrderStepExecutionListener(orderItemWriter, fileGenerationService);
	}

	/**
	 * Define batch job for order file generation.
	 * Uses pagination-based reading to handle large datasets efficiently.
	 */
	@Bean
	public Job orderFileGenerationJob() {
		logger.info("Configuring orderFileGenerationJob");
		return jobBuilderFactory.get("orderFileGenerationJob")
				.incrementer(new RunIdIncrementer())
				.listener(jobExecutionListener())
				.flow(orderFileGenerationStep())
				.end()
				.build();
	}

	/**
	 * Define step for order file generation.
	 * Configures reader, processor, and writer for Order processing.
	 */
	@Bean
	public Step orderFileGenerationStep() {
		logger.info("Configuring orderFileGenerationStep");
		return stepBuilderFactory.get("orderFileGenerationStep")
				.<OrderDto, OrderDto>chunk(chunkSize)
				.reader(orderItemReader)
				.processor(orderItemProcessor)
				.writer(items -> {
					try {
						orderItemWriter.write(items);
					} catch (Exception e) {
						logger.error("Error writing order items", e);
						throw new RuntimeException("Error writing order items", e);
					}
				})
				.listener(new OrderStepExecutionListener(orderItemWriter, fileGenerationService))
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
