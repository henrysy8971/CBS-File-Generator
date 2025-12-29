package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileFinalizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    public GenericXMLWriter(FileFinalizationService fileFinalizationService) {
        this.fileFinalizationService = fileFinalizationService;
    }

    @Override
    public void init(String outputFilePath, String interfaceType) throws IOException {
        this.interfaceType = interfaceType;
        this.finalFilePath = outputFilePath;
        this.partFilePath = outputFilePath + ".part";

        File outputFile = new File(partFilePath);
        if (!outputFile.getParentFile().exists()) {
            outputFile.getParentFile().mkdirs();
        }

        this.fileWriter = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(outputFile.toPath()), StandardCharsets.UTF_8)
        );

        logger.info("GenericXMLWriter initialized - temp file: {}, interfaceType: {}", partFilePath, interfaceType);
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

            // Flush periodically
            if (recordCount % 1000 == 0) {
                fileWriter.flush();
            }
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
            if (headerWritten) {
                String rootElement = resolveRootElement();
                fileWriter.write("  </records>\n");
                fileWriter.write(String.format("  <totalRecords>%d</totalRecords>\n", recordCount));
                fileWriter.write("</" + rootElement + ">\n");
            }

            if (fileWriter != null) {
                fileWriter.close();
            }

            // Finalize file with .part-safe mechanism
            boolean finalized = fileFinalizationService.finalizeFile(partFilePath);
            if (finalized) {
                logger.info("GenericXMLWriter finalized file successfully: {}", finalFilePath);
            } else {
                logger.warn("GenericXMLWriter failed to finalize file: {}", finalFilePath);
            }

        } catch (Exception e) {
            logger.error("Error closing GenericXMLWriter", e);
            throw e;
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
}
