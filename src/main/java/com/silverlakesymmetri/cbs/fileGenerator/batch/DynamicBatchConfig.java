package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
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

@Configuration
public class DynamicBatchConfig {
	private static final Logger logger = LoggerFactory.getLogger(DynamicBatchConfig.class);
	private final JobBuilderFactory jobBuilderFactory;
	private final StepBuilderFactory stepBuilderFactory;
	private final FileGenerationService fileGenerationService;
	private final DynamicItemReader dynamicItemReader;
	private final DynamicItemProcessor dynamicItemProcessor;
	private final DynamicItemWriter dynamicItemWriter;
	private final FileValidationTasklet fileValidationTasklet;
	private final BatchCleanupTasklet batchCleanupTasklet;

	@Value("${file.generation.chunk-size:1000}")
	private int chunkSize;

	@Autowired
	public DynamicBatchConfig(
			JobBuilderFactory jobBuilderFactory,
			StepBuilderFactory stepBuilderFactory,
			FileGenerationService fileGenerationService,
			DynamicItemReader dynamicItemReader,
			DynamicItemProcessor dynamicItemProcessor,
			DynamicItemWriter dynamicItemWriter,
			FileValidationTasklet fileValidationTasklet,
			BatchCleanupTasklet batchCleanupTasklet
	) {
		this.jobBuilderFactory = jobBuilderFactory;
		this.stepBuilderFactory = stepBuilderFactory;
		this.fileGenerationService = fileGenerationService;
		this.dynamicItemReader = dynamicItemReader;
		this.dynamicItemProcessor = dynamicItemProcessor;
		this.dynamicItemWriter = dynamicItemWriter;
		this.fileValidationTasklet = fileValidationTasklet;
		this.batchCleanupTasklet = batchCleanupTasklet;
		logger.info("Configuring FileGeneration with chunk size {}", chunkSize);
	}

	@Bean
	public DynamicStepExecutionListener dynamicStepExecutionListener() {
		return new DynamicStepExecutionListener(fileGenerationService);
	}

	@Bean
	public Step dynamicValidationStep() {
		return stepBuilderFactory.get("dynamicValidationStep")
				.tasklet(fileValidationTasklet)
				.build();
	}

	/**
	 * Defines the generic file generation job.
	 *
	 * @param sharedJobListener Injected from BatchInfrastructureConfig
	 */
	@Bean
	public Job dynamicFileGenerationJob(DynamicJobExecutionListener sharedJobListener) {
		return jobBuilderFactory.get("dynamicFileGenerationJob")
				.incrementer(new RunIdIncrementer())
				.listener(sharedJobListener)
				.start(dynamicFileGenerationStep())
				.on("COMPLETED").to(dynamicValidationStep())
				// If Generation fails, go to cleanup, then FAIL the job
				.from(dynamicFileGenerationStep()).on("*").to(cleanupStep())
				.on("*").fail()
				// If Validation fails, go to cleanup, then FAIL the job
				.from(dynamicValidationStep()).on("FAILED").to(cleanupStep())
				.on("*").fail()
				.from(dynamicValidationStep()).on("COMPLETED").end()
				.end()
				.build();
	}

	/**
	 * Defines the step for dynamic processing.
	 * Uses a step-specific listener to track progress for the dynamic writer.
	 */
	@Bean
	public Step dynamicFileGenerationStep() {
		return stepBuilderFactory.get("dynamicFileGenerationStep")
				.<DynamicRecord, DynamicRecord>chunk(chunkSize)
				.reader(dynamicItemReader)
				.processor(dynamicItemProcessor)
				.writer(dynamicItemWriter)

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
				.listener(dynamicStepExecutionListener())
				.allowStartIfComplete(true)
				.build();
	}

	@Bean
	public Step cleanupStep() {
		return stepBuilderFactory.get("cleanupStep")
				.tasklet(batchCleanupTasklet)
				.build();
	}
}
