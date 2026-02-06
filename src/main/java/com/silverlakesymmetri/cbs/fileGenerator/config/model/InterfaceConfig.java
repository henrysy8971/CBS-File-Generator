package com.silverlakesymmetri.cbs.fileGenerator.config.model;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.MAX_CHUNK_SIZE;

/**
 * Configuration for a data interface used in batch file generation.
 * Designed to be restart-safe and compatible with keySet-pagination readers.
 */
public class InterfaceConfig {
	/* ================= Defaults ================= */
	public static final OutputFormat DEFAULT_OUTPUT_FORMAT = OutputFormat.XML;
	public static final String DEFAULT_FILE_EXTENSION = OutputFormat.XML.name().toLowerCase();

	/* ================= Core (Mandatory, Immutable) ================= */
	private String name;
	private String dataSourceQuery;

	/* ================= Optional (Mutable) ================= */
	private String beanIoMappingFile;
	private boolean haveHeaders = false;
	private String streamName;
	private String xsdSchemaFile;
	private Integer chunkSize = MAX_CHUNK_SIZE;
	private OutputFormat outputFormat = DEFAULT_OUTPUT_FORMAT;
	private String outputFileExtension = DEFAULT_FILE_EXTENSION;
	private String rootElement;
	private String namespace;
	private String keySetColumn;
	private boolean enabled = true;
	private boolean dynamic = false;
	private String description;

	/* ================= Getters / Setters ================= */

	public void setName(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public void setDataSourceQuery(String dataSourceQuery) {
		this.dataSourceQuery = dataSourceQuery;
	}

	public String getDataSourceQuery() {
		return dataSourceQuery;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isDynamic() {
		return dynamic;
	}

	public void setDynamic(boolean dynamic) {
		this.dynamic = dynamic;
	}

	public Integer getChunkSize() {
		return chunkSize;
	}

	public void setChunkSize(Integer chunkSize) {
		this.chunkSize = chunkSize;
	}

	public OutputFormat getOutputFormat() {
		return outputFormat;
	}

	public void setOutputFormat(OutputFormat outputFormat) {
		this.outputFormat = outputFormat != null ? outputFormat : DEFAULT_OUTPUT_FORMAT;
	}

	public String getOutputFileExtension() {
		return outputFileExtension;
	}

	public void setOutputFileExtension(String outputFileExtension) {
		this.outputFileExtension = outputFileExtension != null ? outputFileExtension : DEFAULT_FILE_EXTENSION;
	}

	public String getBeanIoMappingFile() {
		return beanIoMappingFile;
	}

	public void setBeanIoMappingFile(String beanIoMappingFile) {
		this.beanIoMappingFile = beanIoMappingFile;
	}

	public boolean isHaveHeaders() {
		return haveHeaders;
	}

	public void setHaveHeaders(boolean haveHeaders) {
		this.haveHeaders = haveHeaders;
	}

	public String getStreamName() {
		return streamName;
	}

	public void setStreamName(String streamName) {
		this.streamName = streamName;
	}

	public String getXsdSchemaFile() {
		return xsdSchemaFile;
	}

	public void setXsdSchemaFile(String xsdSchemaFile) {
		this.xsdSchemaFile = xsdSchemaFile;
	}

	public String getRootElement() {
		return rootElement;
	}

	public void setRootElement(String rootElement) {
		this.rootElement = rootElement;
	}

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public String getKeySetColumn() {
		return keySetColumn;
	}

	public void setKeySetColumn(String keySetColumn) {
		this.keySetColumn = keySetColumn;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	/* ================= toString ================= */

	@Override
	public String toString() {
		return "InterfaceConfig{" +
				"name='" + name + '\'' +
				", beanioMappingFile='" + beanIoMappingFile + '\'' +
				", haveHeaders=" + haveHeaders +
				", streamName='" + streamName + '\'' +
				", xsdSchemaFile='" + xsdSchemaFile + '\'' +
				", chunkSize=" + chunkSize +
				", outputFormat=" + outputFormat +
				", outputFileExtension='" + outputFileExtension + '\'' +
				", rootElement='" + rootElement + '\'' +
				", namespace='" + namespace + '\'' +
				", keySetColumn='" + keySetColumn + '\'' +
				", enabled=" + enabled +
				", dynamic=" + dynamic +
				", description='" + description + '\'' +
				'}';
	}

	/* ================= Enum ================= */
	public enum OutputFormat {
		XML,
		CSV,
		TXT,
		JSON,
		FIXED
	}
}
