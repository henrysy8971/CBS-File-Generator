package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

@Component
@StepScope
public class GenericXMLWriter implements OutputFormatWriter, StepExecutionListener {
	private static final Logger logger = LoggerFactory.getLogger(GenericXMLWriter.class);
	private static final String RESTART_KEY_OFFSET = "xml.writer.byteOffset";
	private static final String RESTART_KEY_COUNT = "xml.writer.recordCount";
	private final InterfaceConfigLoader interfaceConfigLoader;

	private XMLStreamWriter xmlStreamWriter;
	private ByteTrackingOutputStream byteTrackingStream;
	private BufferedOutputStream bufferedOutputStream;
	private FileOutputStream fileOutputStream;

	private String partFilePath;
	private String interfaceType;
	private String outputFilePath;
	private String rootElement;
	private String itemElement;

	private long recordCount = 0;
	private boolean stepSuccessful = false;

	private final Object lock = new Object(); // Thread-safety for write operations


	public GenericXMLWriter(InterfaceConfigLoader interfaceConfigLoader) {
		this.interfaceConfigLoader = interfaceConfigLoader;
	}

	// --------------------------------------------------
	// Initialization
	// --------------------------------------------------

	@Override
	public void init(String outputFilePath, String interfaceType) throws Exception {
		if (outputFilePath == null || outputFilePath.trim().isEmpty()) {
			throw new IllegalArgumentException("outputFilePath must not be null or empty");
		}

		this.outputFilePath = outputFilePath;

		this.interfaceType = (interfaceType != null && !interfaceType.trim().isEmpty())
				? interfaceType.trim()
				: null;

		partFilePath = outputFilePath.endsWith(".part")
				? outputFilePath
				: outputFilePath + ".part";

		ensureDirectoryExists(outputFilePath);

		rootElement = resolveRootElement();
		itemElement = rootElement + "Item";

		logger.info("Initialized XML writer: {}", partFilePath);
	}

	// --------------------------------------------------
	// Restart Handling
	// --------------------------------------------------
	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		Assert.hasText(outputFilePath, "outputFilePath must not be empty");
		Assert.hasText(interfaceType, "interfaceType must not be empty");

		try {
			File file = new File(partFilePath);

			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
			if (config == null) throw new IllegalArgumentException("No config for " + interfaceType);

			long lastByteOffset = 0;
			boolean isRestart = false;

			if (executionContext.containsKey(RESTART_KEY_OFFSET)) {
				lastByteOffset = executionContext.getLong(RESTART_KEY_OFFSET, 0L);
				if (lastByteOffset < 0) lastByteOffset = 0;
				recordCount = executionContext.getLong(RESTART_KEY_COUNT, 0L);
				logger.info("Restart detected. Truncating file to byte offset: {}, records: {}", lastByteOffset, recordCount);
				isRestart = true;
			}

			fileOutputStream = new FileOutputStream(file, isRestart);
			FileChannel channel = fileOutputStream.getChannel();

			// Truncate if necessary (Critical for restart safety)
			if (isRestart) {
				if (lastByteOffset < channel.size()) {
					channel.truncate(lastByteOffset);
					logger.info("Restart: Truncated file to offset {}, resuming record {}", lastByteOffset, recordCount);
				}
			} else {
				// Fresh run: Truncate to 0 to overwrite any existing garbage
				channel.truncate(0);
			}

			// Force channel to the end (which is now 'offset' or 0)
			// This ensures the byte tracker starts at the exact physical end of file
			long actualPosition = channel.size();

			byteTrackingStream = new ByteTrackingOutputStream(fileOutputStream, actualPosition);
			bufferedOutputStream = new BufferedOutputStream(byteTrackingStream);
			xmlStreamWriter = XMLOutputFactory.newInstance()
					.createXMLStreamWriter(new OutputStreamWriter(bufferedOutputStream, StandardCharsets.UTF_8));

			if (!isRestart) {
				writeHeader();
			}
		} catch (Exception e) {
			closeQuietly();
			throw new ItemStreamException("Failed during restart open()", e);
		}

		logger.info("XML Stream writer initialized for interface={}, output={}",
				interfaceType, partFilePath);
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (xmlStreamWriter == null) throw new IllegalStateException("Writer not opened");
		if (items == null || items.isEmpty()) return;

		synchronized (lock) { // ensure thread-safe writes
			for (DynamicRecord record : items) {
				if (record != null) {
					writeRecordXml(record);
					recordCount++;
				}
			}
		}

		logger.debug("Chunk written: {}, total written: {}", items.size(), recordCount);
	}

	@Override
	public void update(ExecutionContext executionContext) throws ItemStreamException {
		try {
			// Flush cascade: Writer -> Buffer -> ByteTracker -> Disk
			if (xmlStreamWriter != null) xmlStreamWriter.flush();
			if (bufferedOutputStream != null) bufferedOutputStream.flush();
			if (fileOutputStream != null) {
				fileOutputStream.getChannel().force(false);
			}
			if (byteTrackingStream != null) {
				long currentOffset = byteTrackingStream.getBytesWritten();
				executionContext.putLong(RESTART_KEY_OFFSET, currentOffset);
				executionContext.putLong(RESTART_KEY_COUNT, recordCount);
				logger.debug("Saved restart state: bytes={}, records={}", currentOffset, recordCount);
			}
		} catch (Exception e) {
			throw new ItemStreamException("Failed to update ExecutionContext", e);
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
						logger.warn("Step failed. Footer NOT written to allow safe restart.");
					}
					xmlStreamWriter.flush();
					xmlStreamWriter.close();
					xmlStreamWriter = null;
				}
			} catch (Exception e) {
				logger.error("Failed closing XML writer", e);
			} finally {
				closeQuietly();
			}
		}
	}

	// --------------------------------------------------
	// Listeners
	// --------------------------------------------------
	@Override
	public void beforeStep(StepExecution stepExecution) {
		stepSuccessful = false;
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		if (stepExecution == null) return null;
		stepSuccessful = (stepExecution.getStatus() == BatchStatus.COMPLETED);
		return stepExecution.getExitStatus();
	}

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

	private void closeQuietly() {
		try {
			if (xmlStreamWriter != null) xmlStreamWriter.close();
		} catch (Exception ignored) {
		}
		try {
			if (bufferedOutputStream != null) bufferedOutputStream.close();
		} catch (Exception ignored) {
		}
		try {
			if (fileOutputStream != null) fileOutputStream.close();
		} catch (Exception ignored) {
		}
		xmlStreamWriter = null;
		bufferedOutputStream = null;
		fileOutputStream = null;
	}

	private void writeHeader() throws IOException {
		try {
			xmlStreamWriter.writeStartDocument("UTF-8", "1.0");
			xmlStreamWriter.writeStartElement(rootElement);
			xmlStreamWriter.writeStartElement("records");
			xmlStreamWriter.flush();
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

	private String resolveRootElement() {
		if (interfaceType == null) return "data";
		return interfaceType.toLowerCase(Locale.ROOT).replaceFirst("(?i)_interface$", "");
	}

	private String sanitizeElementName(String name) {
		if (name == null || name.trim().isEmpty()) return "field";
		// Ensure starts with letter or underscore, then alphanumeric/underscore
		String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "_");
		if (Character.isDigit(sanitized.charAt(0))) {
			return "_" + sanitized;
		}
		return sanitized;
	}

	// --------------------------------------------------
	// Automatic output directory creation if non existing
	// --------------------------------------------------
	private void ensureDirectoryExists(String path) throws IOException {
		Path parent = Paths.get(path).toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}

	// --------------------------------------------------
	// Inner Class: Byte Tracking
	// --------------------------------------------------
	private static class ByteTrackingOutputStream extends OutputStream {
		private final OutputStream delegate;
		private long bytesWritten;

		public ByteTrackingOutputStream(OutputStream delegate, long initialOffset) {
			this.delegate = delegate;
			bytesWritten = initialOffset;
		}

		@Override
		public void write(int b) throws IOException {
			delegate.write(b);
			bytesWritten++;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			delegate.write(b, off, len);
			bytesWritten += len;
		}

		@Override
		public void write(byte[] b) throws IOException {
			delegate.write(b);
			bytesWritten += b.length;
		}

		@Override
		public void flush() throws IOException {
			delegate.flush();
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}

		public long getBytesWritten() {
			return bytesWritten;
		}
	}
}