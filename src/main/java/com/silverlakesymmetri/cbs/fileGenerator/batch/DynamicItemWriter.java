package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@StepScope
public class DynamicItemWriter implements OutputFormatWriter, ItemStreamWriter<DynamicRecord> {
	private static final Logger logger = LoggerFactory.getLogger(DynamicItemWriter.class);
	private static final String CONTEXT_KEY_PART_FILE = "dynamic.writer.partFilePath";
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

		if (delegateWriter == null) {
			try {
				this.delegateWriter = writerFactory.selectWriter(interfaceType);

				// RESTORE STATE: ExecutionContext is the source of truth for restarts
				String existingPartFile = executionContext.getString(CONTEXT_KEY_PART_FILE, null);
				long existingCount = executionContext.getLong(CONTEXT_KEY_RECORD_COUNT, 0);

				if (delegateWriter instanceof GenericXMLWriter) {
					((GenericXMLWriter) delegateWriter).setInitialRecordCount(existingCount);
				}

				if (existingPartFile != null) {
					delegateWriter.init(existingPartFile, interfaceType);
					logger.info("Restart detected. Resuming [{}] from: {}", interfaceType, existingPartFile);
				} else {
					delegateWriter.init(outputFilePath, interfaceType);
					logger.info("New execution. Initializing [{}] at: {}", interfaceType, outputFilePath);
				}
			} catch (Exception e) {
				throw new ItemStreamException("Failed to initialize delegate writer during open()", e);
			}
		}
	}

	@Override
	public void update(ExecutionContext executionContext) {
		// Periodically called by Spring Batch to save the current progress
		if (delegateWriter != null) {
			executionContext.putString(CONTEXT_KEY_PART_FILE, delegateWriter.getPartFilePath());
			executionContext.putLong(CONTEXT_KEY_RECORD_COUNT, delegateWriter.getRecordCount());
			executionContext.putLong(CONTEXT_KEY_SKIPPED_COUNT, delegateWriter.getSkippedCount());
		}
	}

	@Override
	public void init(String outputFilePath, String interfaceType) {
		// No-op: The framework calls open() which handles the delegate setup.
		// We keep this to satisfy the interface contract.
	}

	@Override
	public void close() {
		if (delegateWriter != null) {
			try {
				delegateWriter.close();
			} catch (Exception e) {
				throw new ItemStreamException("Error closing delegate writer", e);
			}
		}
	}

	@Override
	public long getRecordCount() {
		return delegateWriter != null ? delegateWriter.getRecordCount() : 0;
	}

	@Override
	public long getSkippedCount() {
		return delegateWriter != null ? delegateWriter.getSkippedCount() : 0;
	}

	@Override
	public String getPartFilePath() {
		return delegateWriter != null ? delegateWriter.getPartFilePath() : null;
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (delegateWriter == null) {
			throw new IllegalStateException("Delegate writer not initialized. open() may have failed.");
		}
		delegateWriter.write(items);
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

	public void setStepSuccessful(boolean success) {
		if (delegateWriter instanceof GenericXMLWriter) {
			((GenericXMLWriter) delegateWriter).setStepSuccessful(success);
		}
	}
}
