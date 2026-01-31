package com.silverlakesymmetri.cbs.fileGenerator.dto;

import com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants;

import java.util.Date;

public class HealthResponse {
	private FileGenerationConstants.HealthStatus status;
	private Date timestamp;
	private int interfacesLoaded;
	private long pendingJobs;
	private FileGenerationConstants.SystemLoad systemLoad;
	private SchedulerHealth scheduler;
	private String error;

	public FileGenerationConstants.HealthStatus getStatus() {
		return status;
	}

	public void setStatus(FileGenerationConstants.HealthStatus status) {
		this.status = status;
	}

	public Date getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(Date timestamp) {
		this.timestamp = timestamp;
	}

	public int getInterfacesLoaded() {
		return interfacesLoaded;
	}

	public void setInterfacesLoaded(int interfacesLoaded) {
		this.interfacesLoaded = interfacesLoaded;
	}

	public long getPendingJobs() {
		return pendingJobs;
	}

	public void setPendingJobs(long pendingJobs) {
		this.pendingJobs = pendingJobs;
	}

	public FileGenerationConstants.SystemLoad getSystemLoad() {
		return systemLoad;
	}

	public void setSystemLoad(FileGenerationConstants.SystemLoad systemLoad) {
		this.systemLoad = systemLoad;
	}

	public SchedulerHealth getScheduler() {
		return scheduler;
	}

	public void setScheduler(SchedulerHealth scheduler) {
		this.scheduler = scheduler;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}
}
