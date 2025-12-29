package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileFinalizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Generic XML writer for dynamic records.
 * Writes to .part first, then finalizes file with FileFinalizationService
 */
@Component
@StepScope
public class GenericXMLWriter implements OutputFormatWriter {

	private static final Logger logger = LoggerFactory.getLogger(GenericXMLWriter.class);

	private final FileFinalizationService fileFinalizationService;

	private BufferedWriter fileWriter;
	private String partFilePath;
	private String finalFilePath;
	private long recordCount = 0;
	private String interfaceType;
	private boolean headerWritten = false;
	private boolean stepSuccessful = false;

	public GenericXMLWriter(FileFinalizationService fileFinalizationService) {
		this.fileFinalizationService = fileFinalizationService;
	}

	@Override
	public void init(String outputFilePath, String interfaceType) throws IOException {
		this.interfaceType = interfaceType;
		this.partFilePath = outputFilePath.endsWith(".part") ? outputFilePath : outputFilePath + ".part";

		File outputFile = new File(partFilePath);

		// RESTART LOGIC: Check if file exists and has data
		boolean isRestart = outputFile.exists() && outputFile.length() > 0;

		if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();

		// Use append=true for restarts
		this.fileWriter = new BufferedWriter(new OutputStreamWriter(
				new FileOutputStream(outputFile, isRestart), StandardCharsets.UTF_8));

		if (isRestart) {
			this.headerWritten = true; // Don't write header again
			logger.info("GenericXMLWriter resuming existing .part file: {}", partFilePath);
		} else {
			logger.info("GenericXMLWriter created new .part file: {}", partFilePath);
		}
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (items == null || items.isEmpty()) {
			return;
		}

		for (DynamicRecord record : items) {
			if (!headerWritten) {
				writeXmlHeader();
				headerWritten = true;
			}
			writeRecord(record);
			recordCount++;

			fileWriter.flush();
		}

		logger.debug("Wrote {} records to temp file {}", items.size(), partFilePath);
	}

	private void writeXmlHeader() throws IOException {
		String rootElement = resolveRootElement();
		fileWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		fileWriter.write(String.format("<%s xmlns=\"http://www.example.com/%s\">\n", rootElement, rootElement));
		fileWriter.write("  <records>\n");
	}

	private void writeRecord(DynamicRecord record) throws IOException {
		String rootElement = resolveRootElement();
		String itemElement = rootElement + "Item";
		fileWriter.write("    <" + itemElement + ">\n");

		for (String columnName : record.getColumnNames()) {
			Object value = record.getValue(columnName);
			if (value != null) {
				String xmlElement = sanitizeElementName(columnName);
				String xmlValue = escapeXml(value.toString());
				fileWriter.write(String.format("      <%s>%s</%s>\n", xmlElement, xmlValue, xmlElement));
			}
		}

		fileWriter.write("    </" + itemElement + ">\n");
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

	private String escapeXml(String text) {
		if (text == null) return "";
		return text.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&apos;");
	}

	private String resolveRootElement() {
		if (interfaceType == null) return "data";
		return interfaceType.toLowerCase(Locale.ROOT).replaceFirst("_interface$", "");
	}

	@Override
	public void close() throws IOException {
		try {
			if (fileWriter != null) {
				// Only write closing tags if the step was 100% successful
				if (headerWritten && stepSuccessful) {
					writeXmlFooter();
				} else {
					logger.warn("Step not successful or no records written. Skipping XML footer for interface: {}", interfaceType);
				}

				fileWriter.flush();
				fileWriter.close();
			}

			// Only rename from .part to final if successful
			if (stepSuccessful) {
				boolean finalized = fileFinalizationService.finalizeFile(partFilePath);
				logger.info("GenericXMLWriter finalized file: {}", finalized);
			}

		} catch (Exception e) {
			logger.error("Error during GenericXMLWriter close", e);
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

	/**
	 * This will be called by the StepListener or DynamicItemWriter
	 * before the close() method is triggered.
	 */
	public void setStepSuccessful(boolean success) {
		this.stepSuccessful = success;
	}

	public void setInitialRecordCount(long count) {
		this.recordCount = count;
	}

	private void writeXmlFooter() throws IOException {
		String rootElement = resolveRootElement();
		fileWriter.write("  </records>\n");
		fileWriter.write(String.format("  <totalRecords>%d</totalRecords>\n", recordCount));
		fileWriter.write("</" + rootElement + ">\n");
		fileWriter.flush();
	}
}
