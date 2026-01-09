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
	private long recordCount = 0;
	private boolean headerWritten = false;
	private boolean stepSuccessful = false;

	public GenericXMLWriter() {
	}

	@Override
	public void init(String outputFilePath, String interfaceType) throws IOException {
		this.interfaceType = interfaceType;
		this.partFilePath = outputFilePath.endsWith(".part") ? outputFilePath : outputFilePath + ".part";

		File outputFile = new File(partFilePath);
		if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();

		// Check restart condition
		boolean isRestart = outputFile.exists() && outputFile.length() > 0;

		try {
			this.outputStream = new BufferedOutputStream(new FileOutputStream(outputFile, true));
			this.xmlStreamWriter = XMLOutputFactory.newInstance()
					.createXMLStreamWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

			if (!isRestart) {
				writeHeader();
			} else {
				headerWritten = true;
				logger.info("Resuming XML .part file: {}", partFilePath);
			}

		} catch (Exception e) {
			throw new IOException("Failed to initialize XMLStreamWriter", e);
		}
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (items == null || items.isEmpty()) return;

		for (DynamicRecord record : items) {
			writeRecordXml(record);
			recordCount++;
		}

		// Flush once per chunk
		xmlStreamWriter.flush();
		outputStream.flush();

		logger.debug("Chunk written: {} records, total so far: {}", items.size(), recordCount);
	}

	private void writeHeader() throws XMLStreamException {
		xmlStreamWriter.writeStartDocument("UTF-8", "1.0");
		String root = resolveRootElement();
		xmlStreamWriter.writeStartElement(root);
		xmlStreamWriter.writeStartElement("records");
		headerWritten = true;
	}

	private void writeRecordXml(DynamicRecord record) throws XMLStreamException {
		String itemElement = resolveRootElement() + "Item";
		xmlStreamWriter.writeStartElement(itemElement);

		for (String column : record.getColumnNames()) {
			Object value = record.getValue(column);
			if (value != null) {
				String element = sanitizeElementName(column);
				xmlStreamWriter.writeStartElement(element);
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
		String sanitized = name.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9_]", "_")
				.replaceAll("_+", "_");
		if (!sanitized.matches("^[a-z_].*")) {
			sanitized = "_" + sanitized;
		}
		return sanitized;
	}

	private String resolveRootElement() {
		if (interfaceType == null) return "data";
		return interfaceType.toLowerCase(Locale.ROOT).replaceFirst("(?i)_interface$", "");
	}

	@Override
	public void close() throws IOException {
		try {
			if (xmlStreamWriter != null) {
				if (headerWritten && stepSuccessful) {
					writeFooter();
				} else {
					logger.warn("Step not successful or no records written. Skipping XML footer.");
				}
				xmlStreamWriter.close();
			}

			if (outputStream != null) outputStream.close();
		} catch (Exception e) {
			logger.error("Error closing GenericXMLWriter", e);
			throw new IOException("Failed to close/finalize XML writer", e);
		}
	}

	@Override
	public long getRecordCount() {
		return recordCount;
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
