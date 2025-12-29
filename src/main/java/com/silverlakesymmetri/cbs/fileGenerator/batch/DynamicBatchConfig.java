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
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

	@Bean
	public Job dynamicFileGenerationJob() {
		logger.info("Configuring dynamicFileGenerationJob with chunk size {}", chunkSize);
		return jobBuilderFactory.get("dynamicFileGenerationJob")
				.incrementer(new RunIdIncrementer())
				.listener(new DynamicJobExecutionListener(fileGenerationService, fileFinalizationService))
				.flow(dynamicFileGenerationStep())
				.end()
				.build();
	}

	@Bean
	public Step dynamicFileGenerationStep() {
		logger.info("Configuring dynamicFileGenerationStep");

		// StepScope ensures a fresh reader/writer instance for each job run
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
