package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.batch.BatchCleanupTasklet;
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
import org.springframework.dao.TransientDataAccessException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;

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
	private final BatchCleanupTasklet batchCleanupTasklet;

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
			FileValidationTasklet fileValidationTasklet, BatchCleanupTasklet batchCleanupTasklet
	) {
		this.jobBuilderFactory = jobBuilderFactory;
		this.stepBuilderFactory = stepBuilderFactory;
		this.fileGenerationService = fileGenerationService;
		this.orderItemReader = orderItemReader;
		this.orderItemProcessor = orderItemProcessor;
		this.orderItemWriter = orderItemWriter;
		this.fileValidationTasklet = fileValidationTasklet;
		this.batchCleanupTasklet = batchCleanupTasklet;
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
				.on(BatchStatus.COMPLETED.name()).to(orderFileValidationStep())
				.from(orderFileGenerationStep()).on("*").to(orderCleanupStep())
				.from(orderFileValidationStep()).on("FAILED").to(orderCleanupStep())
				.from(orderCleanupStep()).on("*").fail()
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

				// --- Fault Tolerance Configuration ---
				.faultTolerant()
				.retry(TransientDataAccessException.class)
				.retry(SQLTransientConnectionException.class)
				.noRetry(SQLSyntaxErrorException.class)
				.retryLimit(3)

				// Skip Logic (skip bad data rows, but crash on system errors)
				.skip(Exception.class)
				.noSkip(IOException.class)
				.noSkip(FileNotFoundException.class)
				.skipLimit(100)

				// --- Listeners ---
				.listener(new OrderStepExecutionListener(orderItemWriter, fileGenerationService))
				.allowStartIfComplete(true)
				.build();
	}

	@Bean
	public Step orderCleanupStep() {
		return stepBuilderFactory.get("orderCleanupStep")
				.tasklet(batchCleanupTasklet)
				.build();
	}
}
