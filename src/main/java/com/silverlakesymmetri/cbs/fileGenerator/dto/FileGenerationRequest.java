package com.silverlakesymmetri.cbs.fileGenerator.dto;

import org.hibernate.validator.constraints.NotBlank;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.INTERFACE_TYPE_LENGTH;

public class FileGenerationRequest {
	@NotBlank
	@Size(max = INTERFACE_TYPE_LENGTH)
	@Pattern(regexp = "^[A-Za-z0-9_]+$")
	private String interfaceType; // Required: must match key in interface-config.json
	@Min(1)
	@Max(10000)
	private Integer chunkSize;
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
