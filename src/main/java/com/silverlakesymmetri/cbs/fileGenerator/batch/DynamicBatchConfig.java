package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import com.silverlakesymmetri.cbs.fileGenerator.tasklets.BatchCleanupTasklet;
import com.silverlakesymmetri.cbs.fileGenerator.tasklets.FileValidationTasklet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
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
import org.springframework.dao.TransientDataAccessException;

import java.io.FileNotFoundException;
import java.io.IOException;
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
	}

	@Bean
	public DynamicStepExecutionListener dynamicStepExecutionListener() {
		return new DynamicStepExecutionListener(dynamicItemWriter, fileGenerationService);
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
		logger.info("Configuring dynamicFileGenerationJob Job");
		return jobBuilderFactory.get("dynamicFileGenerationJob")
				.incrementer(new RunIdIncrementer())
				.listener(sharedJobListener)
				.start(dynamicFileGenerationStep())
				.on(BatchStatus.COMPLETED.name()).to(dynamicValidationStep())
				// If Generation fails, go to cleanup, then FAIL the job
				.from(dynamicFileGenerationStep()).on("*").to(cleanupStep())
				.on("*").fail()
				// If Validation fails, go to cleanup, then FAIL the job
				.from(dynamicValidationStep()).on("FAILED").to(cleanupStep())
				.on("*").fail()
				.end()
				.build();
	}

	/**
	 * Defines the step for dynamic processing.
	 * Uses a step-specific listener to track progress for the dynamic writer.
	 */
	@Bean
	public Step dynamicFileGenerationStep() {
		logger.info("Configuring dynamicFileGenerationStep with chunk size {}", chunkSize);

		return stepBuilderFactory.get("dynamicFileGenerationStep")
				.<DynamicRecord, DynamicRecord>chunk(chunkSize)
				.reader(dynamicItemReader)
				.processor(dynamicItemProcessor)
				.writer(dynamicItemWriter)

				// --- Fault Tolerance Configuration ---
				.faultTolerant()
				.retry(TransientDataAccessException.class)
				.retry(SQLTransientConnectionException.class)
				.noRetry(SQLSyntaxErrorException.class)
				.retryLimit(3)

				// Skip Logic (Skip processing errors, but stop on IO errors)
				.skip(ValidationException.class)
				.skip(DataIntegrityViolationException.class)
				.noSkip(NullPointerException.class)
				.noSkip(Exception.class)
				.noSkip(IOException.class)
				.noSkip(FileNotFoundException.class)
				// Skip up to 100 bad records
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
