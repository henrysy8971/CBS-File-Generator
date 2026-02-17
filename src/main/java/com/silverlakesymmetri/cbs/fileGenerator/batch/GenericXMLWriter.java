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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Component
@StepScope
public class GenericXMLWriter implements OutputFormatWriter, StepExecutionListener, ItemStream {

	private static final Logger logger = LoggerFactory.getLogger(GenericXMLWriter.class);

	private XMLStreamWriter xmlStreamWriter;
	private BufferedOutputStream bufferedStream;
	private ByteTrackingOutputStream byteTrackingStream;

	private String partFilePath;
	private String interfaceType;
	private String rootElement;
	private String itemElement;

	private long recordCount = 0;
	private boolean headerWritten = false;
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

		openStreams(file, false);

		writeHeader();
		headerWritten = true;

		logger.info("Initialized XML writer: {}", partFilePath);
	}

	private void openStreams(File file, boolean append) throws IOException {
		FileOutputStream fos = new FileOutputStream(file, append);
		this.byteTrackingStream = new ByteTrackingOutputStream(fos);
		this.bufferedStream = new BufferedOutputStream(byteTrackingStream);

		try {
			this.xmlStreamWriter = XMLOutputFactory.newInstance()
					.createXMLStreamWriter(
							new OutputStreamWriter(
									bufferedStream,
									StandardCharsets.UTF_8));
		} catch (XMLStreamException e) {
			throw new IOException("Failed to create XMLStreamWriter", e);
		}
	}

	// --------------------------------------------------
	// Restart Handling
	// --------------------------------------------------
	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		try {
			if (executionContext.containsKey(BYTE_OFFSET_KEY)) {
				long offset = executionContext.getLong(BYTE_OFFSET_KEY);
				this.recordCount = executionContext.getLong(RECORD_COUNT_KEY);

				File file = new File(partFilePath);

				RandomAccessFile raf = new RandomAccessFile(file, "rw");
				raf.setLength(offset);
				raf.close();

				logger.info("Restart detected. Truncated file to byte offset {}. Resuming from record {}",
						offset, recordCount);

				openStreams(file, true);
				headerWritten = true;
			}
		} catch (Exception e) {
			throw new ItemStreamException("Failed during restart open()", e);
		}
	}

	@Override
	public void update(ExecutionContext executionContext) throws ItemStreamException {
		try {
			xmlStreamWriter.flush();
			bufferedStream.flush();

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
			xmlStreamWriter.flush();
			bufferedStream.flush();
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

				if (stepSuccessful) {
					writeFooter();
					logger.info("Footer written. XML completed.");
				} else {
					logger.warn("Step failed. Footer not written to allow restart.");
				}

				if (xmlStreamWriter != null) {
					xmlStreamWriter.close();
				}

				if (bufferedStream != null) {
					bufferedStream.close();
				}

			} catch (Exception e) {
				logger.error("Failed closing XML writer", e);
				throw new ItemStreamException("Failed to close XML writer", e);
			}
		}
	}

	// --------------------------------------------------
	// Helpers
	// --------------------------------------------------

	private void ensureParentDirectory(File file)
			throws IOException {

		File parent = file.getParentFile();
		if (parent != null && !parent.exists()) {
			if (!parent.mkdirs()) {
				throw new IOException(
						"Unable to create directory: " + parent);
			}
		}
	}

	private String resolveRootElement() {
		if (interfaceType == null) return "data";
		return interfaceType.toLowerCase(Locale.ROOT)
				.replaceFirst("(?i)_interface$", "");
	}

	private String sanitizeElementName(String name) {
		if (name == null || name.trim().isEmpty()) return "_";
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