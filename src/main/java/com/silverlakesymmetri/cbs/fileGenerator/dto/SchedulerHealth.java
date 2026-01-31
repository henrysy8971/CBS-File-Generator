package com.silverlakesymmetri.cbs.fileGenerator.dto;

public class SchedulerHealth {
	private boolean running;
	private boolean inStandby;
	private String jobName;
	private String error;

	public boolean isRunning() {
		return running;
	}

	public void setRunning(boolean running) {
		this.running = running;
	}

	public boolean isInStandby() {
		return inStandby;
	}

	public void setInStandby(boolean inStandby) {
		this.inStandby = inStandby;
	}

	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}
}
