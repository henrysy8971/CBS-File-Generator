package com.silverlakesymmetri.cbs.fileGenerator.config.model;

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
	private boolean enabled = true;

	/* ================= Optional (Mutable) ================= */

	// BeanIO Related
	private boolean dynamic = false;
	private String dataSourceQuery;
	private String beanIoMappingFile;
	private String keySetColumn;
	private boolean haveHeaders = false;
	private String streamName;

	// XML Related
	private String xsdSchemaFile;
	private OutputFormat outputFormat = DEFAULT_OUTPUT_FORMAT;
	private String outputFileExtension = DEFAULT_FILE_EXTENSION;
	private String rootElement;
	private String namespace;
	private String description;

	/* ================= Getters / Setters ================= */

	public void setName(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
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

	public void setDataSourceQuery(String dataSourceQuery) {
		this.dataSourceQuery = dataSourceQuery;
	}

	public String getDataSourceQuery() {
		return dataSourceQuery;
	}

	public String getBeanIoMappingFile() {
		return beanIoMappingFile;
	}

	public void setBeanIoMappingFile(String beanIoMappingFile) {
		this.beanIoMappingFile = beanIoMappingFile;
	}

	public String getKeySetColumn() {
		return keySetColumn;
	}

	public void setKeySetColumn(String keySetColumn) {
		this.keySetColumn = keySetColumn;
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
				", dynamic=" + dynamic +
				", dataSourceQuery='" + dataSourceQuery + '\'' +
				", beanIoMappingFile='" + beanIoMappingFile + '\'' +
				", keySetColumn='" + keySetColumn + '\'' +
				", haveHeaders=" + haveHeaders +
				", streamName='" + streamName + '\'' +
				", xsdSchemaFile='" + xsdSchemaFile + '\'' +
				", outputFormat=" + outputFormat +
				", outputFileExtension='" + outputFileExtension + '\'' +
				", rootElement='" + rootElement + '\'' +
				", namespace='" + namespace + '\'' +
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
