package com.silverlakesymmetri.cbs.fileGenerator.entity;

import javax.persistence.*;
import java.io.Serializable;
import java.sql.Timestamp;

@Entity
@Table(name = "IF_FILE_GENERATION_AUDIT")
public class FileGenerationAudit implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "AUDIT_ID")
	private Long auditId;

	@Column(name = "JOB_ID", length = 100, nullable = false)
	private String jobId;

	@Column(name = "OLD_STATUS", length = 50)
	private String oldStatus;

	@Column(name = "NEW_STATUS", length = 50, nullable = false)
	private String newStatus;

	@Column(name = "CHANGED_BY", length = 100)
	private String changedBy;

	@Column(
			name = "CHANGED_DATE",
			nullable = false,
			insertable = false,
			updatable = false
	)
	private Timestamp changedDate;

	@Column(name = "REASON", length = 500)
	private String reason;

	// Constructor

	public FileGenerationAudit(
			String jobId,
			String oldStatus,
			String newStatus,
			String changedBy,
			String reason) {
		this.jobId = jobId;
		this.oldStatus = oldStatus;
		this.newStatus = newStatus;
		this.changedBy = changedBy;
		this.reason = reason;
	}

	// ===== Getters and Setters =====

	public Long getAuditId() {
		return auditId;
	}

	public String getJobId() {
		return jobId;
	}

	public void setJobId(String jobId) {
		this.jobId = jobId;
	}

	public String getOldStatus() {
		return oldStatus;
	}

	public void setOldStatus(String oldStatus) {
		this.oldStatus = oldStatus;
	}

	public String getNewStatus() {
		return newStatus;
	}

	public void setNewStatus(String newStatus) {
		this.newStatus = newStatus;
	}

	public String getChangedBy() {
		return changedBy;
	}

	public void setChangedBy(String changedBy) {
		this.changedBy = changedBy;
	}

	public Timestamp getChangedDate() {
		return changedDate;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}
}
