package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.validation.XsdValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@StepScope
public class FileValidationTasklet implements Tasklet {
	private static final Logger logger = LoggerFactory.getLogger(FileValidationTasklet.class);
	private final XsdValidator xsdValidator;
	private final InterfaceConfigLoader interfaceConfigLoader;

	@Autowired
	public FileValidationTasklet(XsdValidator xsdValidator, InterfaceConfigLoader interfaceConfigLoader) {
		this.xsdValidator = xsdValidator;
		this.interfaceConfigLoader = interfaceConfigLoader;
	}

	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		ExecutionContext jobContext = chunkContext.getStepContext().getStepExecution()
				.getJobExecution().getExecutionContext();

		String partFilePath = jobContext.getString("partFilePath");
		String interfaceType = (String) chunkContext.getStepContext().getJobParameters().get("interfaceType");

		// 1. Guard against missing path
		if (partFilePath == null) {
			logger.warn("Validation skipped: 'partFilePath' not found in ExecutionContext for interface {}", interfaceType);
			return RepeatStatus.FINISHED;
		}

		InterfaceConfig interfaceConfig = interfaceConfigLoader.getConfig(interfaceType);

		if (interfaceConfig != null && interfaceConfig.getXsdSchemaFile() != null) {
			File file = new File(partFilePath);

			if (!file.exists()) {
				throw new RuntimeException("Validation Error: File missing at " + partFilePath);
			}

			if (!file.canRead()) {
				throw new RuntimeException("Validation Error: File is not readable (Permissions?) at " + partFilePath);
			}

			String xsdSchema = interfaceConfig.getXsdSchemaFile();
			logger.info("Validating file [{}] against schema [{}]", file.getName(), xsdSchema);

			boolean isValid = xsdValidator.validateFullFile(file, xsdSchema);

			if (!isValid) {
				throw new RuntimeException("XSD Validation failed for file: " + partFilePath + " interface: " + interfaceType);
			}

			logger.info("XSD Validation successful for file: {}, interface: {}", partFilePath, interfaceType);
		} else {
			logger.info("No XSD schema configured for interface {}. Skipping validation.", interfaceType);
		}

		return RepeatStatus.FINISHED;
	}
}
