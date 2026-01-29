package com.silverlakesymmetri.cbs.fileGenerator.entity;

import javax.persistence.*;
import java.sql.Timestamp;

@Entity
@Table(name = "IF_DB_TOKEN")
public class DbToken {
	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "IF_DB_TOKEN_SEQ")
	@SequenceGenerator(name = "IF_DB_TOKEN_SEQ", sequenceName = "IF_DB_TOKEN_SEQ", allocationSize = 1)
	@Column(name = "TOKEN_ID")
	private Long tokenId;

	@Column(name = "TOKEN_VALUE", nullable = false, unique = true)
	private String tokenValue;

	@Column(name = "APPLICATION_NAME", nullable = false, length = 100)
	private String applicationName;

	@Column(name = "ISSUED_BY", length = 50)
	private String issuedBy;

	@Column(name = "ISSUED_DATE")
	private Timestamp issuedDate;

	@Column(name = "EXPIRY_DATE")
	private Timestamp expiryDate;

	@Column(name = "ACTIVE", nullable = false)
	private Boolean active = true;

	@Column(name = "LAST_USED_DATE")
	private Timestamp lastUsedDate;

	// Constructors
	public DbToken() {
	}

	public DbToken(String tokenValue, String applicationName) {
		this.tokenValue = tokenValue;
		this.applicationName = applicationName;
		this.active = true;
		this.issuedDate = new Timestamp(System.currentTimeMillis());
	}

	// Getters and Setters
	public Long getTokenId() {
		return tokenId;
	}

	public void setTokenId(Long tokenId) {
		this.tokenId = tokenId;
	}

	public String getTokenValue() {
		return tokenValue;
	}

	public void setTokenValue(String tokenValue) {
		this.tokenValue = tokenValue;
	}

	public String getApplicationName() {
		return applicationName;
	}

	public void setApplicationName(String applicationName) {
		this.applicationName = applicationName;
	}

	public String getIssuedBy() {
		return issuedBy;
	}

	public void setIssuedBy(String issuedBy) {
		this.issuedBy = issuedBy;
	}

	public Timestamp getIssuedDate() {
		return issuedDate;
	}

	public void setIssuedDate(Timestamp issuedDate) {
		this.issuedDate = issuedDate;
	}

	public Timestamp getExpiryDate() {
		return expiryDate;
	}

	public void setExpiryDate(Timestamp expiryDate) {
		this.expiryDate = expiryDate;
	}

	public Boolean getActive() {
		return active;
	}

	public void setActive(Boolean active) {
		this.active = active;
	}

	public Timestamp getLastUsedDate() {
		return lastUsedDate;
	}

	public void setLastUsedDate(Timestamp lastUsedDate) {
		this.lastUsedDate = lastUsedDate;
	}
}
