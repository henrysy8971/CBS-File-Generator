package com.silverlakesymmetri.cbs.fileGenerator.xml;

import com.silverlakesymmetri.cbs.fileGenerator.dto.ColumnType;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.springframework.oxm.Marshaller;
import org.springframework.oxm.MarshallingFailureException;
import org.springframework.oxm.XmlMappingException;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Result;
import javax.xml.transform.stream.StreamResult;
import java.io.OutputStream;
import java.util.Map;

public class DynamicRecordMarshaller implements Marshaller {

	private boolean suppressNullValues = true;
	private boolean trimValues = true;
	private boolean booleanAsYN = false;
	private String timestampFormat = "yyyy-MM-dd'T'HH:mm:ss";
	private String recordElementName = "record";

	private final ThreadLocal<java.text.SimpleDateFormat> dateFormatter =
			ThreadLocal.withInitial(() -> new java.text.SimpleDateFormat(timestampFormat));

	@Override
	public boolean supports(Class<?> clazz) {
		return DynamicRecord.class.isAssignableFrom(clazz);
	}

	@Override
	public void marshal(Object graph, Result result) throws XmlMappingException {

		if (!(result instanceof StreamResult)) {
			throw new MarshallingFailureException("Only StreamResult supported");
		}

		try {
			DynamicRecord record = (DynamicRecord) graph;
			OutputStream os = ((StreamResult) result).getOutputStream();

			XMLStreamWriter writer = XMLOutputFactory
					.newInstance()
					.createXMLStreamWriter(os, "UTF-8");

			writer.writeStartElement(recordElementName);

			for (Map.Entry<String, Object> entry : record.entrySet()) {

				String name = entry.getKey();
				Object value = entry.getValue();
				ColumnType type = record.getType(name);

				if (shouldSuppress(value)) continue;

				writer.writeStartElement(name);
				writer.writeCharacters(formatValue(value, type));
				writer.writeEndElement();
			}

			writer.writeEndElement();
			writer.flush();

		} catch (Exception e) {
			throw new MarshallingFailureException("Marshalling error", e);
		}
	}

	private boolean shouldSuppress(Object value) {
		if (!suppressNullValues) return false;
		if (value == null) return true;
		return value instanceof String && ((String) value).trim().isEmpty();
	}

	private String formatValue(Object value, ColumnType type) {

		if (value == null) return "";

		if (trimValues && value instanceof String) {
			return ((String) value).trim();
		}

		switch (type) {

			case BOOLEAN:
				if (booleanAsYN) {
					return Boolean.TRUE.equals(value) ? "Y" : "N";
				}
				return value.toString();

			case TIMESTAMP:
				if (value instanceof java.util.Date) {
					return dateFormatter.get().format((java.util.Date) value);
				}
				if (value instanceof java.time.temporal.TemporalAccessor) {
					return java.time.format.DateTimeFormatter
							.ofPattern(timestampFormat)
							.format((java.time.temporal.TemporalAccessor) value);
				}
				return value.toString();

			case DECIMAL:
			case INTEGER:
			case STRING:
			default:
				return value.toString();
		}
	}

	// setters for configuration

	public void setBooleanAsYN(boolean booleanAsYN) {
		this.booleanAsYN = booleanAsYN;
	}

	public void setTimestampFormat(String timestampFormat) {
		this.timestampFormat = timestampFormat;
	}

	public void setSuppressNullValues(boolean suppressNullValues) {
		this.suppressNullValues = suppressNullValues;
	}

	public void setTrimValues(boolean trimValues) {
		this.trimValues = trimValues;
	}

	public void setRecordElementName(String recordElementName) {
		this.recordElementName = recordElementName;
	}
}
