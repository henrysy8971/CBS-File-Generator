package com.silverlakesymmetri.cbs.fileGenerator.dto;

public class FileGenerationResponse {

	private String jobId;
	private String status;
	private String interfaceType;
	private String fileName;
	private Long recordCount;
	private Long skippedRecordCount;
	private Long invalidRecordCount;
	private String message;

	public FileGenerationResponse() {
	}

	public FileGenerationResponse(String status, String message) {
		this.status = status;
		this.message = message;
	}

	public String getJobId() {
		return jobId;
	}

	public void setJobId(String jobId) {
		this.jobId = jobId;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getInterfaceType() {
		return interfaceType;
	}

	public void setInterfaceType(String interfaceType) {
		this.interfaceType = interfaceType;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public Long getRecordCount() {
		return recordCount;
	}

	public void setRecordCount(Long recordCount) {
		this.recordCount = recordCount;
	}

	public void setSkippedRecordCount(Long skippedRecordCount) {
		this.skippedRecordCount = skippedRecordCount;
	}

	public void setInvalidRecordCount(Long invalidRecordCount) {
		this.invalidRecordCount = invalidRecordCount;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}
}
