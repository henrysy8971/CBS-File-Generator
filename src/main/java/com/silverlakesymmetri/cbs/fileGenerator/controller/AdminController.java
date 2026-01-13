package com.silverlakesymmetri.cbs.fileGenerator.controller;

import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
	private final JobLauncher jobLauncher;
	private final Job cleanupJob;
	private final Scheduler scheduler;

	@Autowired
	public AdminController(JobLauncher jobLauncher, Job cleanupJob, Scheduler scheduler) {
		this.jobLauncher = jobLauncher;
		this.cleanupJob = cleanupJob;
		this.scheduler = scheduler;
	}

	@PostMapping("/cleanup")
	public ResponseEntity<String> triggerCleanup() {
		try {
			JobParameters params = new JobParametersBuilder()
					.addLong("time", System.currentTimeMillis())
					.toJobParameters();
			jobLauncher.run(cleanupJob, params);
			return ResponseEntity.ok("Manual cleanup triggered");
		} catch (Exception e) {
			return ResponseEntity.status(500).body("Failed to trigger cleanup: " + e.getMessage());
		}
	}

	@PostMapping("/scheduler/pause")
	public String pauseScheduler() throws SchedulerException {
		scheduler.pauseAll();
		return "Quartz scheduler paused";
	}

	@PostMapping("/scheduler/resume")
	public String resumeScheduler() throws SchedulerException {
		scheduler.resumeAll();
		return "Quartz scheduler resumed";
	}

	@PostMapping("/scheduler/force-run")
	public ResponseEntity<String> forceRunPolling() {
		try {
			// Check if scheduler is actually running first
			if (scheduler.isInStandbyMode()) {
				return ResponseEntity.status(409).body("Cannot force-run: Scheduler is PAUSED");
			}
			scheduler.triggerJob(JobKey.jobKey("fileGenPollJob", "file-generation-group"));
			return ResponseEntity.ok("Polling triggered manually");
		} catch (SchedulerException e) {
			return ResponseEntity.status(500).body("Quartz error: " + e.getMessage());
		}
	}
}
