package com.silverlakesymmetri.cbs.fileGenerator.config.model;

/**
 * Configuration for a data interface used in batch file generation.
 * Designed to be restart-safe and compatible with keyset-pagination readers.
 */
public class InterfaceConfig {

	/* ================= Defaults ================= */
	public static final int DEFAULT_CHUNK_SIZE = 1000;
	public static final OutputFormat DEFAULT_OUTPUT_FORMAT = OutputFormat.XML;
	public static final String DEFAULT_FILE_EXTENSION = "xml";
	public static final String DEFAULT_ROOT_ELEMENT = "records";
	public static final String DEFAULT_NAMESPACE = "";
	public static final String DEFAULT_KEYSET_COLUMN = "id";
	public static final String DEFAULT_STREAM_NAME = "interfaceStream";

	/* ================= Core (Mandatory, Immutable) ================= */
	private String name;
	private String dataSourceQuery;

	/* ================= Optional (Mutable) ================= */
	private String beanioMappingFile;
	private String streamName = DEFAULT_STREAM_NAME;
	private String xsdSchemaFile;
	private int chunkSize = DEFAULT_CHUNK_SIZE;
	private OutputFormat outputFormat = DEFAULT_OUTPUT_FORMAT;
	private String outputFileExtension = DEFAULT_FILE_EXTENSION;
	private String rootElement = DEFAULT_ROOT_ELEMENT;
	private String namespace = DEFAULT_NAMESPACE;
	private String keysetColumn = DEFAULT_KEYSET_COLUMN;
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

	public int getChunkSize() {
		return chunkSize;
	}

	public void setChunkSize(int chunkSize) {
		if (chunkSize <= 0) {
			throw new IllegalArgumentException("chunkSize must be > 0");
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
		this.rootElement = rootElement != null ? rootElement : DEFAULT_ROOT_ELEMENT;
	}

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace != null ? namespace : DEFAULT_NAMESPACE;
	}

	public String getKeysetColumn() {
		return keysetColumn;
	}

	public void setKeysetColumn(String keysetColumn) {
		this.keysetColumn = keysetColumn != null ? keysetColumn : DEFAULT_KEYSET_COLUMN;
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
				", enabled=" + enabled +
				", chunkSize=" + chunkSize +
				", outputFormat=" + outputFormat +
				", rootElement='" + rootElement + '\'' +
				", namespace='" + namespace + '\'' +
				", keysetColumn='" + keysetColumn + '\'' +
				", description='" + description + '\'' +
				'}';
	}

	/* ================= Enum ================= */
	public enum OutputFormat {
		XML,
		CSV,
		FIXED
	}
}
