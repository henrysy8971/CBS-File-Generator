package com.silverlakesymmetri.cbs.fileGenerator.config.model;

import java.util.Collections;
import java.util.Map;

public class InterfaceConfig {

	/* ================= Defaults ================= */

	public static final int DEFAULT_CHUNK_SIZE = 1000;
	public static final OutputFormat DEFAULT_OUTPUT_FORMAT = OutputFormat.XML;
	public static final String DEFAULT_FILE_EXTENSION = "xml";

	/* ================= Core ================= */

	private String name;
	private String dataSourceQuery;
	private boolean enabled = true;
	private int chunkSize = DEFAULT_CHUNK_SIZE;

	/* ================= Output ================= */

	private OutputFormat outputFormat = DEFAULT_OUTPUT_FORMAT;
	private String outputFileExtension = DEFAULT_FILE_EXTENSION;
	private String beanioMappingFile;

	/* ================= XSD Validation ================= */

	private String xsdSchemaFile;

	/**
	 * 0 = OFF
	 * 1 = FIRST_RECORD
	 * 2 = SAMPLE
	 * 3 = ALL
	 */
	private int xsdValidationMode = 0;
	private int xsdSampleRate = 100;

	/* ================= XML Mapping ================= */

	private String rootElement;
	private String namespace;

	/**
	 * Maps source column → XML element name
	 */
	private Map<String, String> fieldMappings = Collections.emptyMap();

	/* ================= Transform Rules ================= */

	private Map<String, Object> transformRules = Collections.emptyMap();

	/* ================= Constructors ================= */

	public InterfaceConfig() {
		// for Jackson
	}

	public InterfaceConfig(String name, String dataSourceQuery) {
		this.name = name;
		this.dataSourceQuery = dataSourceQuery;
	}

	/* ================= Getters / Setters ================= */

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDataSourceQuery() {
		return dataSourceQuery;
	}

	public void setDataSourceQuery(String dataSourceQuery) {
		this.dataSourceQuery = dataSourceQuery;
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
		this.outputFormat = outputFormat != null
				? outputFormat
				: DEFAULT_OUTPUT_FORMAT;
	}

	public String getOutputFileExtension() {
		return outputFileExtension;
	}

	public void setOutputFileExtension(String outputFileExtension) {
		this.outputFileExtension = outputFileExtension != null
				? outputFileExtension
				: DEFAULT_FILE_EXTENSION;
	}

	public String getBeanioMappingFile() {
		return beanioMappingFile;
	}

	public void setBeanioMappingFile(String beanioMappingFile) {
		this.beanioMappingFile = beanioMappingFile;
	}

	public String getXsdSchemaFile() {
		return xsdSchemaFile;
	}

	public void setXsdSchemaFile(String xsdSchemaFile) {
		this.xsdSchemaFile = xsdSchemaFile;
	}

	public int getXsdValidationMode() {
		return xsdValidationMode;
	}

	public void setXsdValidationMode(int xsdValidationMode) {
		this.xsdValidationMode = xsdValidationMode;
	}

	public int getXsdSampleRate() {
		return xsdSampleRate;
	}

	public void setXsdSampleRate(int xsdSampleRate) {
		if (xsdSampleRate <= 0) {
			throw new IllegalArgumentException("xsdSampleRate must be > 0");
		}
		this.xsdSampleRate = xsdSampleRate;
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

	public Map<String, String> getFieldMappings() {
		return fieldMappings;
	}

	public void setFieldMappings(Map<String, String> fieldMappings) {
		this.fieldMappings = fieldMappings != null
				? fieldMappings
				: Collections.emptyMap();
	}

	public Map<String, Object> getTransformRules() {
		return transformRules;
	}

	public void setTransformRules(Map<String, Object> transformRules) {
		this.transformRules = transformRules != null
				? transformRules
				: Collections.emptyMap();
	}

	/* ================= Helper ================= */

	public boolean isXsdValidationEnabled() {
		return xsdSchemaFile != null && xsdValidationMode > 0;
	}

	@Override
	public String toString() {
		return "InterfaceConfig{" +
				"name='" + name + '\'' +
				", enabled=" + enabled +
				", chunkSize=" + chunkSize +
				", outputFormat=" + outputFormat +
				", xsdValidationMode=" + xsdValidationMode +
				'}';
	}

	/* ================= Enum ================= */

	public enum OutputFormat {
		XML,
		CSV,
		FIXED
	}
}
