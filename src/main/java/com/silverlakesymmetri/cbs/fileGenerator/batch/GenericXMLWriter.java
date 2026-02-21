package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Component
@StepScope
public class GenericXMLWriter extends AbstractBaseOutputWriter<DynamicRecord> implements OutputFormatWriter {
	private XMLStreamWriter xmlStreamWriter;
	private String rootElement;
	private String itemElement;

	public GenericXMLWriter() {
	}

	@Override
	protected String getByteOffsetKey() {
		return "xml.writer.byteOffset"; //
	}

	@Override
	protected String getRecordCountKey() {
		return "xml.writer.recordCount"; //
	}

	@Override
	protected void onInit() {
		// Resolve XML specific naming conventions
		this.rootElement = (interfaceType == null) ? "data" :
				interfaceType.toLowerCase(Locale.ROOT).replaceFirst("(?i)_interface$", "");
		this.itemElement = rootElement + "Item";
	}

	@Override
	protected void openStream(OutputStream os, boolean isRestart) throws Exception {
		// Initialize the StAX writer wrapping the shared tracking stream
		xmlStreamWriter = XMLOutputFactory.newInstance()
				.createXMLStreamWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));

		if (!isRestart) {
			writeHeader();
		}
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (xmlStreamWriter == null) throw new IllegalStateException("XML Writer not opened");

		for (DynamicRecord record : items) {
			if (record != null) {
				try {
					writeRecordXml(record);
					recordCount++; //
				} catch (Exception e) {
					logger.error("Failed writing record: {}", record, e);
					throw e;
				}
			}
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

	@Override
	protected void flushInternal() throws Exception {
		if (xmlStreamWriter != null) {
			xmlStreamWriter.flush(); //
		}
	}

	@Override
	protected void writeHeader() throws Exception {
		xmlStreamWriter.writeStartDocument("UTF-8", "1.0");
		xmlStreamWriter.writeStartElement(rootElement);
		xmlStreamWriter.writeStartElement("records");
		xmlStreamWriter.flush(); //
	}

	@Override
	protected void writeFooter() throws Exception {
		xmlStreamWriter.writeEndElement(); // close "records"
		xmlStreamWriter.writeStartElement("totalRecords");
		xmlStreamWriter.writeCharacters(String.valueOf(recordCount));
		xmlStreamWriter.writeEndElement();
		xmlStreamWriter.writeEndElement(); // close root element
		xmlStreamWriter.writeEndDocument();
		xmlStreamWriter.flush(); //
	}

	@Override
	public void close() {
		try {
			if (xmlStreamWriter != null) {
				if (stepSuccessful) {
					writeFooter(); //
				}
				xmlStreamWriter.close();
			}
		} catch (Exception e) {
			logger.error("Error closing XML stream for {}", interfaceType, e);
		} finally {
			super.closeQuietly(); //
		}
	}

	private String sanitizeElementName(String name) {
		if (name == null || name.trim().isEmpty()) return "field";
		String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "_");
		if (Character.isDigit(sanitized.charAt(0))) {
			return "_" + sanitized;
		}
		return sanitized; //
	}
}