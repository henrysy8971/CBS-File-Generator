package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.FILE_GEN_PART_FILE_PATH;
import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.FILE_GEN_TOTAL_RECORD_COUNT;

@Component
@StepScope
public class DynamicItemWriter implements ItemStreamWriter<DynamicRecord>, StepExecutionListener {
	private static final Logger logger = LoggerFactory.getLogger(DynamicItemWriter.class);
	private static final String CONTEXT_KEY_RECORD_COUNT = "dynamic.writer.recordCount";
	private static final String CONTEXT_KEY_SKIPPED_COUNT = "dynamic.writer.skippedCount";
	private final OutputFormatWriterFactory writerFactory;

	private OutputFormatWriter delegateWriter;
	private String interfaceType;
	private String outputFilePath;

	@Autowired
	public DynamicItemWriter(OutputFormatWriterFactory writerFactory) {
		this.writerFactory = writerFactory;
	}

	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {

		ensureDelegateInitialized();

		try {
			// RESTORE STATE: ExecutionContext is the source of truth for restarts
			String existingPartFile = executionContext.getString(FILE_GEN_PART_FILE_PATH);
			if (existingPartFile != null && !Files.exists(Paths.get(existingPartFile))) {
				logger.warn("Expected part file not found: {}", existingPartFile);
			}

			String pathToUse = (existingPartFile != null) ? existingPartFile : outputFilePath;

			delegateWriter.init(pathToUse, interfaceType);
			delegateWriter.open(executionContext);

			if (existingPartFile != null) {
				logger.info("Restart detected. Resuming [{}] from: {}", interfaceType, existingPartFile);
			} else {
				logger.info("New execution. Initializing [{}] at: {}", interfaceType, outputFilePath);
			}
		} catch (Exception e) {
			throw new ItemStreamException("Failed to initialize delegate writer during open()", e);
		}
	}

	@Override
	public void update(ExecutionContext executionContext) {
		// Periodically called by Spring Batch to save the current progress
		if (delegateWriter != null) {
			delegateWriter.update(executionContext);
			executionContext.putString(FILE_GEN_PART_FILE_PATH, delegateWriter.getPartFilePath());
			executionContext.putLong(CONTEXT_KEY_RECORD_COUNT, delegateWriter.getRecordCount());
			executionContext.putLong(CONTEXT_KEY_SKIPPED_COUNT, delegateWriter.getSkippedCount());
		}
	}

	@Override
	public void close() {
		if (delegateWriter != null) {
			try {
				delegateWriter.close();
			} catch (Exception e) {
				logger.error("Error closing delegate writer", e);
			}
		}
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (delegateWriter == null) {
			throw new IllegalStateException("Delegate writer not initialized. open() may have failed.");
		}
		delegateWriter.write(items);
	}

	@Override
	public void beforeStep(StepExecution stepExecution) {
		if (delegateWriter instanceof StepExecutionListener) {
			((StepExecutionListener) delegateWriter).beforeStep(stepExecution);
			logger.debug("Delegate synchronized with StepExecution writeCount");
		}
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		// Populate Job Context with metadata for the JobListener to rename/move the file
		ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
		jobContext.putString(FILE_GEN_PART_FILE_PATH, delegateWriter.getPartFilePath());
		jobContext.putLong(FILE_GEN_TOTAL_RECORD_COUNT, delegateWriter.getRecordCount());
		if (delegateWriter instanceof StepExecutionListener) {
			StepExecutionListener listener = (StepExecutionListener) delegateWriter;
			return listener.afterStep(stepExecution);
		}
		return stepExecution.getExitStatus();
	}

	// Setters for JobParameters (Injected by Spring Batch via @Value)
	@Value("#{jobParameters['interfaceType']}")
	public void setInterfaceType(String interfaceType) {
		this.interfaceType = interfaceType;
	}

	@Value("#{jobParameters['outputFilePath']}")
	public void setOutputFilePath(String outputFilePath) {
		this.outputFilePath = outputFilePath;
	}

	private void ensureDelegateInitialized() {
		ensureParametersPresent();
		if (this.delegateWriter == null) {
			try {
				this.delegateWriter = writerFactory.selectWriter(interfaceType);
			} catch (Exception e) {
				throw new IllegalStateException("Critical: Could not create delegate writer for " + interfaceType, e);
			}
		}
	}

	private void ensureParametersPresent() {
		if (interfaceType == null || outputFilePath == null) {
			throw new IllegalStateException("JobParameters interfaceType and outputFilePath are required.");
		}
	}
}
