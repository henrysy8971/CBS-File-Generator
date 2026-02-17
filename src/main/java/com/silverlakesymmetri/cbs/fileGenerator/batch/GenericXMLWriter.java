package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import com.silverlakesymmetri.cbs.fileGenerator.xml.DynamicRecordMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.xml.StaxEventItemWriter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Component
@StepScope
public class GenericXMLWriter implements OutputFormatWriter {
	private static final Logger logger = LoggerFactory.getLogger(GenericXMLWriter.class);

	private final DynamicRecordMarshaller marshaller;
	private StaxEventItemWriter<DynamicRecord> delegate;
	private static final String RESTART_KEY_COUNT = "xml.record.count";

	private String outputFilePath;
	private String interfaceType;
	private long recordCount = 0;

	public GenericXMLWriter(DynamicRecordMarshaller marshaller) {
		this.marshaller = marshaller;
	}

	// ============================================================
	// INIT (called from DynamicItemWriter)
	// ============================================================

	@Override
	public void init(String outputFilePath, String interfaceType) throws Exception {
		if (outputFilePath == null || outputFilePath.trim().isEmpty()) {
			throw new IllegalArgumentException("outputFilePath must not be empty");
		}

		this.interfaceType = interfaceType;
		this.outputFilePath = outputFilePath.endsWith(".part")
				? outputFilePath
				: outputFilePath + ".part";

		ensureDirectoryExists(this.outputFilePath);

		delegate = new StaxEventItemWriter<>();
		delegate.setName("genericXmlWriter");
		delegate.setResource(new FileSystemResource(this.outputFilePath));
		delegate.setMarshaller(marshaller);
		delegate.setRootTagName(resolveRootTag());
		delegate.setEncoding("UTF-8");
		delegate.setSaveState(true);
		delegate.setOverwriteOutput(false);
		delegate.afterPropertiesSet();

		logger.info("Initialized XML writer for interface={} file={}",
				interfaceType, this.outputFilePath);
	}

	// ============================================================
	// SPRING BATCH LIFECYCLE
	// ============================================================

	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		Assert.hasText(outputFilePath, "outputFilePath must not be empty");
		Assert.hasText(interfaceType, "interfaceType must not be empty");
		delegate.open(executionContext);
		recordCount = executionContext.getLong(RESTART_KEY_COUNT, 0L);
		logger.info("XML writer opened. Resuming from recordCount={}", recordCount);
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (items == null || items.isEmpty()) return;
		delegate.write(items);
		recordCount += items.size();
	}

	@Override
	public void update(ExecutionContext executionContext) throws ItemStreamException {
		executionContext.putLong(RESTART_KEY_COUNT, recordCount);
		delegate.update(executionContext);
	}

	@Override
	public void close() throws ItemStreamException {
		delegate.close();
		logger.info("XML writing completed. Total records={}", recordCount);
	}

	// ============================================================
	// OutputFormatWriter Contract
	// ============================================================

	@Override
	public long getRecordCount() {
		return recordCount;
	}

	@Override
	public long getSkippedCount() {
		return 0;
	}

	@Override
	public String getOutputFilePath() {
		return outputFilePath;
	}

	// ============================================================
	// Helpers
	// ============================================================

	private void ensureDirectoryExists(String path) throws Exception {
		Path parent = Paths.get(path).toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}

	private String resolveRootTag() {
		if (interfaceType == null || interfaceType.trim().isEmpty()) {
			return "records";
		}

		return interfaceType
				.toLowerCase()
				.replaceFirst("(?i)_interface$", "");
	}
}
