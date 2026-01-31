package com.silverlakesymmetri.cbs.fileGenerator.controller;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.exception.ConflictException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.FILE_GEN_GROUP;
import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.FILE_GEN_POLL_JOB;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
	private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
	private final JobLauncher jobLauncher;
	private final Job cleanupJob;
	private final Scheduler scheduler;
	private final InterfaceConfigLoader interfaceConfigLoader;

	@Autowired
	public AdminController(JobLauncher jobLauncher,
						   Job cleanupJob,
						   Scheduler scheduler,
						   InterfaceConfigLoader interfaceConfigLoader) {
		this.jobLauncher = jobLauncher;
		this.cleanupJob = cleanupJob;
		this.scheduler = scheduler;
		this.interfaceConfigLoader = interfaceConfigLoader;
	}

	@PostMapping("/cleanup")
	public ResponseEntity<String> triggerCleanup() throws Exception {
		JobParameters params = new JobParametersBuilder()
				.addLong("time", System.currentTimeMillis())
				.toJobParameters();
		jobLauncher.run(cleanupJob, params);
		return ResponseEntity.ok("Manual cleanup triggered");
	}

	@PostMapping("/scheduler/pause")
	public ResponseEntity<String> pauseScheduler() throws SchedulerException {
		scheduler.pauseAll();
		return ResponseEntity.ok("Quartz scheduler paused");
	}

	@PostMapping("/scheduler/resume")
	public ResponseEntity<String> resumeScheduler() throws SchedulerException {
		scheduler.resumeAll();
		return ResponseEntity.ok("Quartz scheduler resumed");
	}

	@PostMapping("/scheduler/force-run")
	public ResponseEntity<String> forceRunPolling() throws SchedulerException {
		// Check if scheduler is actually running first
		if (scheduler.isInStandbyMode()) {
			throw new ConflictException("Cannot force-run: Scheduler is PAUSED");
		}

		scheduler.triggerJob(JobKey.jobKey(FILE_GEN_POLL_JOB, FILE_GEN_GROUP));
		return ResponseEntity.ok("Polling triggered manually");
	}

	@PostMapping("/reload-config")
	public ResponseEntity<String> reloadConfig() {
		logger.info("Admin request: Reloading interface-config.json from classpath");
		interfaceConfigLoader.loadConfigs();
		int count = interfaceConfigLoader.getAllConfigs().size();
		return ResponseEntity.ok("Configuration reloaded successfully. Total interfaces: " + count);
	}
}
