package com.silverlakesymmetri.cbs.fileGenerator.entity;

import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationStatus;

import javax.persistence.*;
import java.sql.Timestamp;

@Entity
@Table(name = "FILE_GENERATION")
public class FileGeneration {
	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "FILE_GEN_SEQ")
	@SequenceGenerator(name = "FILE_GEN_SEQ", sequenceName = "FILE_GEN_SEQ", allocationSize = 1)
	@Column(name = "FILE_GEN_ID")
	private Long fileGenId;

	@Column(name = "JOB_ID", nullable = false, unique = true)
	private String jobId;

	@Column(name = "INTERFACE_TYPE", length = 100)
	private String interfaceType;

	@Column(name = "FILE_NAME", nullable = false)
	private String fileName;

	@Column(name = "FILE_PATH", nullable = false)
	private String filePath;

	@Column(name = "STATUS", nullable = false, length = 20)
	private String status; // PENDING, PROCESSING, STOPPED, FINALIZING, COMPLETED, FAILED

	@Column(name = "RECORD_COUNT")
	private Long recordCount;

	@Column(name = "SKIPPED_RECORD_COUNT")
	private Long skippedRecordCount;

	@Column(name = "INVALID_RECORD_COUNT")
	private Long invalidRecordCount;

	@Lob
	@Column(name = "ERROR_MESSAGE")
	private String errorMessage;

	@Column(name = "CREATED_BY", length = 50)
	private String createdBy;

	@Column(name = "CREATED_DATE")
	private Timestamp createdDate;

	@Column(name = "COMPLETED_DATE")
	private Timestamp completedDate;
	/**
	 * OPTIMISTIC LOCKING VERSION FIELD
	 * Hibernate will automatically increment this on every update.
	 * If two threads try to update the same record, the second one will fail
	 * because the version won't match.
	 */

	@Version
	@Column(name = "VERSION")
	private Integer version;

	public Integer getVersion() {
		return version;
	}

	public void setVersion(Integer version) {
		this.version = version;
	}

	// Constructors
	public FileGeneration() {
	}

	public FileGeneration(String jobId, String fileName, String filePath) {
		this.jobId = jobId;
		this.fileName = fileName;
		this.filePath = filePath;
		this.status = FileGenerationStatus.PENDING.name();
		this.createdDate = new Timestamp(System.currentTimeMillis());
	}

	// Getters and Setters
	public Long getFileGenId() {
		return fileGenId;
	}

	public void setFileGenId(Long fileGenId) {
		this.fileGenId = fileGenId;
	}

	public String getJobId() {
		return jobId;
	}

	public void setJobId(String jobId) {
		this.jobId = jobId;
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

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Long getRecordCount() {
		return recordCount;
	}

	public void setRecordCount(Long recordCount) {
		this.recordCount = recordCount;
	}

	public Long getSkippedRecordCount() {
		return skippedRecordCount;
	}

	public void setSkippedRecordCount(Long skippedRecordCount) {
		this.skippedRecordCount = skippedRecordCount;
	}

	public Long getInvalidRecordCount() {
		return invalidRecordCount;
	}

	public void setInvalidRecordCount(Long invalidRecordCount) {
		this.invalidRecordCount = invalidRecordCount;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public Timestamp getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(Timestamp createdDate) {
		this.createdDate = createdDate;
	}

	public Timestamp getCompletedDate() {
		return completedDate;
	}

	public void setCompletedDate(Timestamp completedDate) {
		this.completedDate = completedDate;
	}

	@PrePersist
	protected void onCreate() {
		if (this.createdDate == null) {
			this.createdDate = new Timestamp(System.currentTimeMillis());
		}
	}
}
