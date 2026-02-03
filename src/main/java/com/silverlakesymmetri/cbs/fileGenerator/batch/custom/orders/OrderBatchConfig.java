package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.batch.DynamicJobExecutionListener;
import com.silverlakesymmetri.cbs.fileGenerator.batch.FileValidationTasklet;
import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.ORDER_INTERFACE;

@Configuration
public class OrderBatchConfig {
	private static final Logger logger = LoggerFactory.getLogger(OrderBatchConfig.class);
	private final JobBuilderFactory jobBuilderFactory;
	private final StepBuilderFactory stepBuilderFactory;
	private final FileGenerationService fileGenerationService;
	private final OrderItemReader orderItemReader;
	private final OrderItemProcessor orderItemProcessor;
	private final OrderItemWriter orderItemWriter;
	private final FileValidationTasklet fileValidationTasklet;

	@Value("${file.generation.chunk-size:1000}")
	private int chunkSize;

	@Autowired
	public OrderBatchConfig(
			JobBuilderFactory jobBuilderFactory,
			StepBuilderFactory stepBuilderFactory,
			FileGenerationService fileGenerationService,
			OrderItemReader orderItemReader,
			OrderItemProcessor orderItemProcessor,
			OrderItemWriter orderItemWriter,
			FileValidationTasklet fileValidationTasklet
	) {
		this.jobBuilderFactory = jobBuilderFactory;
		this.stepBuilderFactory = stepBuilderFactory;
		this.fileGenerationService = fileGenerationService;
		this.orderItemReader = orderItemReader;
		this.orderItemProcessor = orderItemProcessor;
		this.orderItemWriter = orderItemWriter;
		this.fileValidationTasklet = fileValidationTasklet;
	}

	// Define the Job Listener as a Bean
	@Bean
	public Step orderFileValidationStep() {
		return stepBuilderFactory.get("orderFileValidationStep")
				.tasklet(fileValidationTasklet)
				.build();
	}

	@Bean(name = ORDER_INTERFACE)
	public Job orderFileGenerationJob(DynamicJobExecutionListener sharedJobListener) {
		return jobBuilderFactory.get(ORDER_INTERFACE)
				.incrementer(new RunIdIncrementer())
				.listener(sharedJobListener)
				.start(orderFileGenerationStep())
				.on(BatchStatus.COMPLETED.name()).to(orderFileValidationStep()) // Move to validation only on success
				.on("*").fail() // Fail the job for any other status (FAILED, STOPPED)
				.end()
				.build();
	}

	/**
	 * Defines the specialized step for Order processing.
	 * Configures reader, processor, and writer for Order processing.
	 * Uses OrderDto instead of generic DynamicRecord.
	 */
	@Bean
	public Step orderFileGenerationStep() {
		logger.info("Configuring orderFileGenerationStep with chunk size {}", chunkSize);
		return stepBuilderFactory.get("orderFileGenerationStep")
				.<OrderDto, OrderDto>chunk(chunkSize)
				.reader(orderItemReader)
				.processor(orderItemProcessor)
				.writer(orderItemWriter)
				.listener(new OrderStepExecutionListener(orderItemWriter, fileGenerationService))
				.allowStartIfComplete(true) // allows restart with .part handling
				.build();
	}
}
