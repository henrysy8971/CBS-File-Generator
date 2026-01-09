package com.silverlakesymmetri.cbs.fileGenerator.dto;

public class FileGenerationRequest {
	private String interfaceType; // Required: must match key in interface-config.json
	private Integer chunkSize;
	private String dataSourceQuery;
	private String xsdSchemaName;

	public FileGenerationRequest() {
	}

	public FileGenerationRequest(String interfaceType) {
		this.interfaceType = interfaceType;
	}

	public String getInterfaceType() {
		return interfaceType;
	}

	public void setInterfaceType(String interfaceType) {
		this.interfaceType = interfaceType;
	}

	public String getDataSourceQuery() {
		return dataSourceQuery;
	}

	public void setDataSourceQuery(String dataSourceQuery) {
		this.dataSourceQuery = dataSourceQuery;
	}

	public Integer getChunkSize() {
		return chunkSize;
	}

	public void setChunkSize(Integer chunkSize) {
		this.chunkSize = chunkSize;
	}

	public String getXsdSchemaName() {
		return xsdSchemaName;
	}

	public void setXsdSchemaName(String xsdSchemaName) {
		this.xsdSchemaName = xsdSchemaName;
	}
}
