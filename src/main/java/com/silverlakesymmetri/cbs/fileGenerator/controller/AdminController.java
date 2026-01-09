package com.silverlakesymmetri.cbs.fileGenerator.controller;

import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
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
	public String triggerCleanup() throws Exception {
		JobParameters params = new JobParametersBuilder()
				.addLong("time", System.currentTimeMillis())
				.toJobParameters();
		jobLauncher.run(cleanupJob, params);
		return "Cleanup job triggered successfully";
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
	public String forceRunPolling() throws SchedulerException {
		// This allows you to trigger the "FileGenerationScheduler" immediately
		// without waiting for the next Cron cycle.
		scheduler.triggerJob(JobKey.jobKey("fileGenPollJob", "file-generation-group"));
		return "File generation polling job triggered manually";
	}
}
