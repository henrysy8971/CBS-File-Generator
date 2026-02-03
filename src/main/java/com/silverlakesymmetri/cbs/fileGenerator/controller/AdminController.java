package com.silverlakesymmetri.cbs.fileGenerator.controller;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.exception.ConflictException;
import com.silverlakesymmetri.cbs.fileGenerator.exception.NotFoundException;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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

	/**
	 * Manually trigger a specific File Generation via Quartz.
	 * This allows the admin to bypass the cron schedule.
	 */
	@PostMapping("/scheduler/trigger/{interfaceType}")
	public ResponseEntity<String> triggerInterfaceJob(@PathVariable String interfaceType) throws SchedulerException {
		String upperType = interfaceType.toUpperCase();

		// 1. Validate that the interface exists in our config
		if (Boolean.FALSE.equals(interfaceConfigLoader.interfaceExists(upperType))) {
			throw new NotFoundException("Interface type not found in configuration: " + upperType);
		}

		// 2. Prepare the data map for Quartz
		// This ensures the Quartz Job knows which interface to process
		JobDataMap jobDataMap = new JobDataMap();
		jobDataMap.put("interfaceType", upperType);
		jobDataMap.put("triggeredBy", "ADMIN_REST_API");

		// 3. Trigger the Poller Job immediately with these specific parameters
		// Note: We trigger the FILE_GEN_POLL_JOB which is capable of handling the specific type
		scheduler.triggerJob(JobKey.jobKey(FILE_GEN_POLL_JOB, FILE_GEN_GROUP), jobDataMap);

		logger.info("Admin manually triggered Quartz job for interface: {}", upperType);
		return ResponseEntity.ok("Manual trigger for " + upperType + " sent to Quartz scheduler.");
	}

	@GetMapping("/scheduler/jobs")
	public ResponseEntity<List<Map<String, Object>>> listScheduledJobs() throws SchedulerException {
		List<Map<String, Object>> jobs = new ArrayList<>();

		// Iterate through all job groups
		for (String groupName : scheduler.getJobGroupNames()) {
			// Get all job keys in this group
			for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName))) {

				List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
				Date nextFireTime = null;
				String cronExpression = "N/A";

				if (!triggers.isEmpty()) {
					Trigger trigger = triggers.get(0);
					nextFireTime = trigger.getNextFireTime();
					if (trigger instanceof CronTrigger) {
						cronExpression = ((CronTrigger) trigger).getCronExpression();
					}
				}

				Map<String, Object> jobInfo = new HashMap<>();
				jobInfo.put("jobName", jobKey.getName());
				jobInfo.put("group", jobKey.getGroup());
				jobInfo.put("nextFireTime", nextFireTime);
				jobInfo.put("cronExpression", cronExpression);
				jobInfo.put("status", scheduler.getTriggerState(triggers.get(0).getKey()).name());

				jobs.add(jobInfo);
			}
		}

		return ResponseEntity.ok(jobs);
	}

	@GetMapping("/scheduler/status")
	public ResponseEntity<List<Map<String, Object>>> getDetailedJobStatus() throws SchedulerException {
		List<Map<String, Object>> scheduledJobs = new ArrayList<>();

		// 1. Get all Job groups (e.g., 'file-generation-group')
		for (String groupName : scheduler.getJobGroupNames()) {

			// 2. Get all Jobs in that group
			for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName))) {

				JobDetail jobDetail = scheduler.getJobDetail(jobKey);
				List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);

				for (Trigger trigger : triggers) {
					Map<String, Object> info = new LinkedHashMap<>();

					// Extract Business Info from the JobDataMap
					String interfaceType = jobDetail.getJobDataMap().getString("interfaceType");

					info.put("jobName", jobKey.getName());
					info.put("interfaceType", interfaceType != null ? interfaceType : "SYSTEM_TASK");
					info.put("triggerName", trigger.getKey().getName());
					info.put("nextRunTime", trigger.getNextFireTime());
					info.put("previousRunTime", trigger.getPreviousFireTime());

					// Get the Cron Expression if it's a CronTrigger
					if (trigger instanceof CronTrigger) {
						info.put("cronExpression", ((CronTrigger) trigger).getCronExpression());
					}

					// Get current status (NORMAL, PAUSED, BLOCKED, ERROR, etc.)
					Trigger.TriggerState state = scheduler.getTriggerState(trigger.getKey());
					info.put("status", state.name());

					scheduledJobs.add(info);
				}
			}
		}
		return ResponseEntity.ok(scheduledJobs);
	}
}
