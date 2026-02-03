package com.silverlakesymmetri.cbs.fileGenerator.config.model;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.MAX_CHUNK_SIZE;
import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.MIN_CHUNK_SIZE;

/**
 * Configuration for a data interface used in batch file generation.
 * Designed to be restart-safe and compatible with keyset-pagination readers.
 */
public class InterfaceConfig {
	/* ================= Defaults ================= */
	public static final OutputFormat DEFAULT_OUTPUT_FORMAT = OutputFormat.XML;
	public static final String DEFAULT_FILE_EXTENSION = OutputFormat.XML.name().toLowerCase();

	/* ================= Core (Mandatory, Immutable) ================= */
	private String name;
	private String dataSourceQuery;

	/* ================= Optional (Mutable) ================= */
	private String beanioMappingFile;
	private boolean haveHeaders = false;
	private String streamName;
	private String xsdSchemaFile;
	private Integer chunkSize = MAX_CHUNK_SIZE;
	private OutputFormat outputFormat = DEFAULT_OUTPUT_FORMAT;
	private String outputFileExtension = DEFAULT_FILE_EXTENSION;
	private String rootElement;
	private String namespace;
	private String keysetColumn;
	private boolean enabled = true;
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

	public Integer getChunkSize() {
		return chunkSize;
	}

	public void setChunkSize(Integer chunkSize) {
		if (chunkSize < MIN_CHUNK_SIZE) {
			throw new IllegalArgumentException("chunkSize must be >= " + MIN_CHUNK_SIZE);
		}
		if (chunkSize > MAX_CHUNK_SIZE) {
			throw new IllegalArgumentException("chunkSize must be <= " + MAX_CHUNK_SIZE);
		}
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

	public String getBeanioMappingFile() {
		return beanioMappingFile;
	}

	public void setBeanioMappingFile(String beanioMappingFile) {
		this.beanioMappingFile = beanioMappingFile;
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

	public String getKeysetColumn() {
		return keysetColumn;
	}

	public void setKeysetColumn(String keysetColumn) {
		this.keysetColumn = keysetColumn;
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
				", dataSourceQuery='" + dataSourceQuery + '\'' +
				", beanioMappingFile='" + beanioMappingFile + '\'' +
				", streamName='" + streamName + '\'' +
				", xsdSchemaFile='" + xsdSchemaFile + '\'' +
				", chunkSize=" + chunkSize +
				", outputFormat=" + outputFormat +
				", outputFileExtension='" + outputFileExtension + '\'' +
				", rootElement='" + rootElement + '\'' +
				", namespace='" + namespace + '\'' +
				", keysetColumn='" + keysetColumn + '\'' +
				", enabled=" + enabled +
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
