package com.silverlakesymmetri.cbs.fileGenerator.entity;

import javax.persistence.*;
import java.sql.Timestamp;

@Entity
@Table(name = "IF_APP_CONFIG")
public class AppConfig {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "CONFIG_ID")
	private Long id;

	@Column(name = "CONFIG_KEY", unique = true, nullable = false)
	private String configKey;

	@Column(name = "CONFIG_VALUE")
	private String configValue;

	@Column(name = "CONFIG_TYPE")
	private String configType;

	@Column(name = "DESCRIPTION")
	private String description;

	@Column(name = "ACTIVE", nullable = false)
	private boolean active = true;

	@Column(name = "CREATED_DATE", nullable = false)
	private Timestamp createdDate;

	@Column(name = "UPDATED_DATE", nullable = false)
	private Timestamp updatedDate;

	public AppConfig() {
	}

	public AppConfig(String configKey, String configValue) {
		Timestamp now = new Timestamp(System.currentTimeMillis());
		this.configKey = configKey;
		this.configValue = configValue;
		this.createdDate = now;
		this.updatedDate = now;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getConfigKey() {
		return configKey;
	}

	public void setConfigKey(String configKey) {
		this.configKey = configKey;
	}

	public String getConfigValue() {
		return configValue;
	}

	public void setConfigValue(String configValue) {
		this.configValue = configValue;
	}

	public String getConfigType() {
		return configType;
	}

	public void setConfigType(String configType) {
		this.configType = configType;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public Timestamp getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(Timestamp createdDate) {
		this.createdDate = createdDate;
	}

	public Timestamp getUpdatedDate() {
		return updatedDate;
	}

	public void setUpdatedDate(Timestamp updatedDate) {
		this.updatedDate = updatedDate;
	}
}
