package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Component
@StepScope
public class GenericXMLWriter implements OutputFormatWriter, StepExecutionListener, ItemStream {

	private static final Logger logger = LoggerFactory.getLogger(GenericXMLWriter.class);

	private XMLStreamWriter xmlStreamWriter;
	private ByteTrackingOutputStream byteTrackingStream;
	private FileOutputStream fos;

	private String partFilePath;
	private String interfaceType;
	private String rootElement;
	private String itemElement;

	private long recordCount = 0;
	private boolean stepSuccessful = false;

	private final Object lock = new Object(); // Thread-safety for write operations

	// Keys for ExecutionContext
	private static final String BYTE_OFFSET_KEY = "xml.byte.offset";
	private static final String RECORD_COUNT_KEY = "xml.record.count";

	// --------------------------------------------------
	// StepExecutionListener
	// --------------------------------------------------

	@Override
	public void beforeStep(StepExecution stepExecution) {
		this.stepSuccessful = false;
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		this.stepSuccessful = stepExecution.getStatus() == BatchStatus.COMPLETED;
		return stepExecution.getExitStatus();
	}

	// --------------------------------------------------
	// Initialization
	// --------------------------------------------------

	@Override
	public void init(String outputFilePath, String interfaceType) throws IOException {
		if (outputFilePath == null || outputFilePath.trim().isEmpty()) {
			throw new IllegalArgumentException("outputFilePath must not be null or empty");
		}

		this.interfaceType = (interfaceType != null && !interfaceType.trim().isEmpty())
				? interfaceType.trim()
				: null;

		this.partFilePath = outputFilePath.endsWith(".part")
				? outputFilePath
				: outputFilePath + ".part";

		File file = new File(partFilePath);
		ensureParentDirectory(file);

		this.rootElement = resolveRootElement();
		this.itemElement = rootElement + "Item";

		logger.info("Initialized XML writer: {}", partFilePath);
	}

	// --------------------------------------------------
	// Restart Handling
	// --------------------------------------------------
	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		try {
			File file = new File(partFilePath);
			ensureParentDirectory(file);

			long offset = 0;
			boolean isRestart = false;

			if (executionContext.containsKey(BYTE_OFFSET_KEY)) {
				offset = executionContext.getLong(BYTE_OFFSET_KEY);
				this.recordCount = executionContext.getLong(RECORD_COUNT_KEY);
				isRestart = true;
			}

			// 1. Open FileOutputStream in APPEND mode
			fos = new FileOutputStream(file, true);
			FileChannel channel = fos.getChannel();

			// 2. Truncate if necessary (Critical for restart safety)
			if (isRestart) {
				if (offset < channel.size()) {
					channel.truncate(offset);
					logger.info("Restart: Truncated file to offset {}, resuming record {}", offset, recordCount);
				}
			} else {
				// Fresh run: Truncate to 0 to overwrite any existing garbage
				channel.truncate(0);
			}

			// 3. Wrap in ByteTracker with correct INITIAL offset
			this.byteTrackingStream = new ByteTrackingOutputStream(fos, offset);

			// 4. Create XML Writer
			// We use a non-closing wrapper or simply be careful not to close byteTrackingStream twice
			this.xmlStreamWriter = XMLOutputFactory.newInstance()
					.createXMLStreamWriter(new OutputStreamWriter(byteTrackingStream, StandardCharsets.UTF_8));

			// 5. Write Header if new file
			if (!isRestart) {
				writeHeader();
			}
		} catch (Exception e) {
			throw new ItemStreamException("Failed during restart open()", e);
		}
	}

	@Override
	public void update(ExecutionContext executionContext) throws ItemStreamException {
		try {
			if (xmlStreamWriter != null) {
				xmlStreamWriter.flush();
				byteTrackingStream.flush();
			}

			long currentOffset = byteTrackingStream.getBytesWritten();
			executionContext.putLong(BYTE_OFFSET_KEY, currentOffset);
			executionContext.putLong(RECORD_COUNT_KEY, recordCount);

			logger.debug("Saved restart state: bytes={}, records={}",
					currentOffset, recordCount);
		} catch (Exception e) {
			throw new ItemStreamException("Failed to update ExecutionContext", e);
		}
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (items == null || items.isEmpty()) return;

		synchronized (lock) { // ensure thread-safe writes
			for (DynamicRecord record : items) {
				if (record != null) {
					writeRecordXml(record);
					recordCount++;
				}
			}
		}

		logger.debug("Chunk written: {} records, total written: {}", items.size(), recordCount);
	}

	private void writeHeader() throws IOException {
		try {
			xmlStreamWriter.writeStartDocument("UTF-8", "1.0");
			xmlStreamWriter.writeStartElement(rootElement);
			xmlStreamWriter.writeStartElement("records");
		} catch (XMLStreamException e) {
			throw new IOException("Failed to write header", e);
		}
	}

	private void writeRecordXml(DynamicRecord record) throws XMLStreamException {
		xmlStreamWriter.writeStartElement(itemElement);

		for (String column : record.keySet()) {
			Object value = record.get(column);
			if (value != null) {
				xmlStreamWriter.writeStartElement(sanitizeElementName(column));
				xmlStreamWriter.writeCharacters(value.toString());
				xmlStreamWriter.writeEndElement();
			}
		}

		xmlStreamWriter.writeEndElement(); // itemElement
	}

	private void writeFooter() throws IOException {
		try {
			xmlStreamWriter.writeEndElement(); // records
			xmlStreamWriter.writeStartElement("totalRecords");
			xmlStreamWriter.writeCharacters(String.valueOf(recordCount));
			xmlStreamWriter.writeEndElement();
			xmlStreamWriter.writeEndElement(); // root element
			xmlStreamWriter.writeEndDocument();
			xmlStreamWriter.flush();
		} catch (XMLStreamException e) {
			throw new IOException("Failed to write footer", e);
		}
	}

	@Override
	public void close() throws ItemStreamException {
		synchronized (lock) {
			try {
				if (xmlStreamWriter != null) {
					if (stepSuccessful) {
						writeFooter();
						logger.info("Footer written. XML completed.");
					} else {
						logger.warn("Step failed. Footer not written to allow restart.");
					}
					xmlStreamWriter.close();
				}
			} catch (Exception e) {
				logger.error("Failed closing XML writer", e);
			} finally {
				// Ensure FOS is closed if XML writer failed
				try {
					if (fos != null) fos.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	// --------------------------------------------------
	// Helpers
	// --------------------------------------------------

	private void ensureParentDirectory(File file) throws IOException {
		File parent = file.getParentFile();
		if (parent != null && !parent.exists()) {
			if (!parent.mkdirs()) {
				throw new IOException("Unable to create directory: " + parent);
			}
		}
	}

	private String resolveRootElement() {
		if (interfaceType == null) return "data";
		return interfaceType.toLowerCase(Locale.ROOT).replaceFirst("(?i)_interface$", "");
	}

	private String sanitizeElementName(String name) {
		if (name == null || name.trim().isEmpty()) return "field";
		String sanitized = name.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9_]", "_")
				.replaceAll("_+", "_")
				.replaceAll("^_+|_+$", "");

		if (!sanitized.matches("^[a-z_].*")) {
			sanitized = "_" + sanitized;
		}

		return sanitized.isEmpty() ? "_" : sanitized;
	}

	// --------------------------------------------------

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
		return partFilePath;
	}
}