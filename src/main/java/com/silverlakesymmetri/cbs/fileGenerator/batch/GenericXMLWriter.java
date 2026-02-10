package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Generic XML writer for dynamic records.
 * Fully XMLStreamWriter-based, restart-safe, flushes per chunk.
 */
@Component
@StepScope
public class GenericXMLWriter implements OutputFormatWriter {
	private static final Logger logger = LoggerFactory.getLogger(GenericXMLWriter.class);
	private XMLStreamWriter xmlStreamWriter;
	private BufferedOutputStream outputStream;
	private String partFilePath;
	private String interfaceType;
	private String rootElement;
	private String itemElement;
	private long recordCount = 0;
	private long skippedCount = 0;
	private boolean headerWritten = false;
	private boolean stepSuccessful = false;
	private final Object lock = new Object(); // Thread-safety for write operations

	public GenericXMLWriter() {
	}

	@Override
	public void init(String outputFilePath, String interfaceType) throws IOException {
		if (outputFilePath == null || outputFilePath.trim().isEmpty()) {
			throw new IllegalArgumentException("outputFilePath must not be null or empty");
		}

		this.interfaceType = (interfaceType != null && !interfaceType.trim().isEmpty())
				? interfaceType.trim()
				: null;

		this.partFilePath = outputFilePath.endsWith(".part") ? outputFilePath : outputFilePath + ".part";

		File outputFile = new File(partFilePath);
		ensureParentDirectory(outputFile);

		// Check restart condition
		boolean isRestart = outputFile.exists() && outputFile.length() > 0;

		try {
			this.outputStream = new BufferedOutputStream(new FileOutputStream(outputFile, true));
			this.xmlStreamWriter = XMLOutputFactory.newInstance()
					.createXMLStreamWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

			// Cache element names
			this.rootElement = resolveRootElement();
			this.itemElement = rootElement + "Item";

			if (isRestart) {
				headerWritten = true;
				logger.info("Resuming XML .part file: {}", partFilePath);
			} else {
				writeHeader();
			}

		} catch (Exception e) {
			closeQuietly();
			throw new IOException("Failed to initialize XMLStreamWriter", e);
		}
	}

	private void ensureParentDirectory(File file) throws IOException {
		File parentDir = file.getParentFile();
		if (parentDir != null) {
			if (parentDir.exists()) {
				if (!parentDir.isDirectory()) {
					throw new IOException("Parent path exists but is not a directory: " + parentDir.getAbsolutePath());
				}
			} else if (!parentDir.mkdirs()) {
				throw new IOException("Unable to create output directory: " + parentDir.getAbsolutePath());
			}
		}
	}

	private void closeQuietly() {
		try {
			if (xmlStreamWriter != null) xmlStreamWriter.close();
		} catch (Exception ignored) {
		}

		try {
			if (outputStream != null) outputStream.close();
		} catch (Exception ignored) {
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
				} else {
					skippedCount++;
				}
			}
			xmlStreamWriter.flush();
			outputStream.flush();
		}

		logger.debug("Chunk written: {} records, total written: {}, total skipped: {}", items.size(), recordCount, skippedCount);
	}

	private void writeHeader() throws XMLStreamException {
		xmlStreamWriter.writeStartDocument("UTF-8", "1.0");
		xmlStreamWriter.writeStartElement(rootElement);
		xmlStreamWriter.writeStartElement("records");
		headerWritten = true;
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

	private void writeFooter() throws XMLStreamException {
		xmlStreamWriter.writeEndElement(); // records
		xmlStreamWriter.writeStartElement("totalRecords");
		xmlStreamWriter.writeCharacters(String.valueOf(recordCount));
		xmlStreamWriter.writeEndElement();
		xmlStreamWriter.writeEndElement(); // root element
		xmlStreamWriter.writeEndDocument();
		xmlStreamWriter.flush();
	}

	private String sanitizeElementName(String name) {
		if (name == null || name.trim().isEmpty()) return "_";

		String sanitized = name.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9_]", "_")
				.replaceAll("_+", "_")
				.replaceAll("^_+|_+$", ""); // trim leading/trailing underscores

		if (!sanitized.matches("^[a-z_].*")) {
			sanitized = "_" + sanitized;
		}
		return sanitized.isEmpty() ? "_" : sanitized;
	}

	private String resolveRootElement() {
		if (interfaceType == null) return "data";
		return interfaceType.toLowerCase(Locale.ROOT).replaceFirst("(?i)_interface$", "");
	}

	@Override
	public void close() throws IOException {
		synchronized (lock) {
			try {
				if (xmlStreamWriter != null) {
					try {
						if (headerWritten && stepSuccessful) {
							writeFooter();
						} else {
							logger.warn("Step not successful. Writing footer for well-formed partial XML.");
							writeFooter(); // ensures XML remains well-formed
						}
					} catch (XMLStreamException e) {
						throw new IOException("Failed writing XML footer", e);
					}
					xmlStreamWriter.close();
				}

				if (outputStream != null) outputStream.close();

			} catch (Exception e) {
				logger.error("Error closing GenericXMLWriter", e);
				throw new IOException("Failed to close/finalize XML writer", e);
			}
		}
	}

	@Override
	public long getRecordCount() {
		return recordCount;
	}

	@Override
	public long getSkippedCount() {
		return skippedCount;
	}

	@Override
	public String getPartFilePath() {
		return partFilePath;
	}

	public void setStepSuccessful(boolean stepSuccessful) {
		this.stepSuccessful = stepSuccessful;
	}

	public void setInitialRecordCount(long count) {
		this.recordCount = count;
	}
}
