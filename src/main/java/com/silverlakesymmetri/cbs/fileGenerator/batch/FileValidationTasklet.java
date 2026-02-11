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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

@Component
@StepScope
public class FileValidationTasklet implements Tasklet {
	private static final Logger logger = LoggerFactory.getLogger(FileValidationTasklet.class);
	private final InterfaceConfigLoader interfaceConfigLoader;
	private final XsdValidator xsdValidator;
	private final boolean strictMode;

	@Autowired
	public FileValidationTasklet(XsdValidator xsdValidator,
								 InterfaceConfigLoader interfaceConfigLoader,
								 @Value("${validation.xsd.strict-mode:false}") boolean strictMode) {
		this.xsdValidator = xsdValidator;
		this.interfaceConfigLoader = interfaceConfigLoader;
		this.strictMode = strictMode;
	}

	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
		ExecutionContext jobContext = chunkContext.getStepContext().getStepExecution()
				.getJobExecution().getExecutionContext();

		// 1. Retrieve file path and guard against missing path
		String partFilePath = jobContext.getString("partFilePath", null);
		if (partFilePath == null || partFilePath.trim().isEmpty()) {
			logger.warn("Validation skipped: 'partFilePath' missing or empty in ExecutionContext.");
			return RepeatStatus.FINISHED;
		}
		partFilePath = partFilePath.trim();

		Object param = chunkContext.getStepContext()
				.getJobParameters()
				.get("interfaceType");

		// 2. Retrieve interfaceType
		String interfaceType = param != null ? param.toString() : "UNKNOWN";

		// 3. Load interface configuration
		InterfaceConfig interfaceConfig = interfaceConfigLoader.getConfig(interfaceType);
		if (interfaceConfig == null || interfaceConfig.getXsdSchemaFile() == null) {
			logger.info("No XSD schema configured for interface {}. Skipping validation.", interfaceType);
			return RepeatStatus.FINISHED;
		}

		// 4. Validate input file
		File file = new File(partFilePath);
		ensureFileReadable(file);

		String xsdSchema = interfaceConfig.getXsdSchemaFile();
		boolean isValid = xsdValidator.validateFullFile(file, xsdSchema);

		if (!isValid) {
			if (strictMode) {
				throw new ValidationException("XSD validation failed for file: " +
						file.getAbsolutePath() + ", interface: " + interfaceType);
			} else {
				logger.warn("XSD validation failed (lenient mode) for file: {}, interface: {}",
						file.getAbsolutePath(), interfaceType);
				return RepeatStatus.FINISHED;
			}
		}

		logger.info("XSD validation successful for file: {}, interface: {}", file.getAbsolutePath(), interfaceType);
		return RepeatStatus.FINISHED;
	}

	/**
	 * Ensures the file exists and is readable.
	 *
	 * @param file File to check
	 * @throws ValidationException if file is missing or unreadable
	 */
	@SuppressWarnings("EmptyTryBlock")
	private void ensureFileReadable(File file) throws ValidationException {
		if (file == null) {
			throw new ValidationException("File reference is null");
		}

		String desc = "Input file: " + file.getName();

		if (!file.exists()) {
			throw new ValidationException(desc + " missing at path: " + file.getAbsolutePath());
		}

		if (!file.isFile() || !file.canRead()) {
			throw new ValidationException(desc + " is not a regular readable file: " + file.getAbsolutePath());
		}

		// Verify readability by opening a stream
		try (FileInputStream ignored = new FileInputStream(file)) {
			// File can be opened → readable
		} catch (IOException e) {
			throw new ValidationException(desc + " is not readable (permissions?) at path: " + file.getAbsolutePath(), e);
		}
	}

	/**
	 * Exception class for validation errors.
	 */
	public static class ValidationException extends RuntimeException {
		public ValidationException(String message) {
			super(message);
		}

		public ValidationException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
