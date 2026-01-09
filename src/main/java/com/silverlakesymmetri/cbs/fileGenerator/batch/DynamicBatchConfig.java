package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

	@Value("${file.generation.chunk-size:1000}")
	private int chunkSize;

	public DynamicBatchConfig(
			JobBuilderFactory jobBuilderFactory,
			StepBuilderFactory stepBuilderFactory,
			FileGenerationService fileGenerationService,
			DynamicItemReader dynamicItemReader,
			DynamicItemProcessor dynamicItemProcessor,
			DynamicItemWriter dynamicItemWriter,
			FileValidationTasklet fileValidationTasklet
	) {
		this.jobBuilderFactory = jobBuilderFactory;
		this.stepBuilderFactory = stepBuilderFactory;
		this.fileGenerationService = fileGenerationService;
		this.dynamicItemReader = dynamicItemReader;
		this.dynamicItemProcessor = dynamicItemProcessor;
		this.dynamicItemWriter = dynamicItemWriter;
		this.fileValidationTasklet = fileValidationTasklet;
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
				.on("COMPLETED").to(dynamicValidationStep()) // Only validate if success
				.on("*").fail() // Fail if anything else happens
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
				.listener(new DynamicStepExecutionListener(dynamicItemWriter, fileGenerationService))
				.allowStartIfComplete(true) // allows restart with .part handling
				.build();
	}
}
