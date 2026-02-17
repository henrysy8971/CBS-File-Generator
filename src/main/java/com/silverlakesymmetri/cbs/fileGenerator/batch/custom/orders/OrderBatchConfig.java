package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.batch.listeners.JobExecutionListener;
import com.silverlakesymmetri.cbs.fileGenerator.batch.listeners.StepExecutionListener;
import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import com.silverlakesymmetri.cbs.fileGenerator.tasklets.BatchCleanupTasklet;
import com.silverlakesymmetri.cbs.fileGenerator.tasklets.FileValidationTasklet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.item.validator.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.TransientDataAccessException;

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
			FileValidationTasklet fileValidationTasklet,
			BatchCleanupTasklet batchCleanupTasklet
	) {
		this.jobBuilderFactory = jobBuilderFactory;
		this.stepBuilderFactory = stepBuilderFactory;
		this.fileGenerationService = fileGenerationService;
		this.orderItemReader = orderItemReader;
		this.orderItemProcessor = orderItemProcessor;
		this.orderItemWriter = orderItemWriter;
		this.fileValidationTasklet = fileValidationTasklet;
		this.batchCleanupTasklet = batchCleanupTasklet;
		logger.info("Configuring Order FileGeneration with chunk size {}", chunkSize);
	}

	@Bean
	public StepExecutionListener orderStepExecutionListener() {
		return new StepExecutionListener(fileGenerationService);
	}

	@Bean
	public Step orderFileValidationStep() {
		return stepBuilderFactory.get("orderFileValidationStep")
				.tasklet(fileValidationTasklet)
				.build();
	}

	@Bean(name = ORDER_INTERFACE)
	public Job orderFileGenerationJob(JobExecutionListener sharedJobListener) {
		return jobBuilderFactory.get(ORDER_INTERFACE)
				.incrementer(new RunIdIncrementer())
				.listener(sharedJobListener)
				.start(orderFileGenerationStep())
				.on("COMPLETED").to(orderFileValidationStep())
				// If Generation fails, go to cleanup, then FAIL the job
				.from(orderFileGenerationStep()).on("*").to(orderCleanupStep())
				.on("*").fail()
				// If Validation fails, go to cleanup, then FAIL the job
				.from(orderFileValidationStep()).on("FAILED").to(orderCleanupStep())
				.on("*").fail()
				.from(orderFileValidationStep()).on("COMPLETED").end()
				.end()
				.build();
	}

	@Bean
	public Step orderFileGenerationStep() {
		return stepBuilderFactory.get("orderFileGenerationStep")
				.<OrderDto, OrderDto>chunk(chunkSize)
				.reader(orderItemReader)
				.processor(orderItemProcessor)
				.writer(orderItemWriter)

				// --- Fault Tolerance Configuration ---
				.faultTolerant()
				.retry(TransientDataAccessException.class)
				.retry(SQLTransientConnectionException.class)
				.retry(DeadlockLoserDataAccessException.class)
				.noRetry(SQLSyntaxErrorException.class)
				.retryLimit(3)

				// Skip Logic (skip bad data rows, but crash on system errors)
				.skip(ValidationException.class)
				.skip(DataIntegrityViolationException.class)
				.skipLimit(100)

				// --- Listeners ---
				.listener(orderStepExecutionListener())
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
