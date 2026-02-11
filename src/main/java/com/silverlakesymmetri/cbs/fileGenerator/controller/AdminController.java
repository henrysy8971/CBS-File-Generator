package com.silverlakesymmetri.cbs.fileGenerator.controller;

import com.silverlakesymmetri.cbs.fileGenerator.batch.BeanIOFormatWriter;
import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.dto.PagedResponse;
import com.silverlakesymmetri.cbs.fileGenerator.exception.ConflictException;
import com.silverlakesymmetri.cbs.fileGenerator.exception.NotFoundException;
import com.silverlakesymmetri.cbs.fileGenerator.validation.XsdValidator;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
	private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
	private final JobLauncher jobLauncher;
	private final Job cleanupJob;
	private final Scheduler scheduler;
	private final XsdValidator xsdValidator;
	private final InterfaceConfigLoader interfaceConfigLoader;

	@Autowired
	public AdminController(JobLauncher jobLauncher,
						   @Qualifier("cleanupJob") Job cleanupJob,
						   Scheduler scheduler, XsdValidator xsdValidator,
						   InterfaceConfigLoader interfaceConfigLoader) {
		this.jobLauncher = jobLauncher;
		this.cleanupJob = cleanupJob;
		this.scheduler = scheduler;
		this.xsdValidator = xsdValidator;
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
		logger.info("Admin request: Reloading configurations...");

		// 1. Reload JSON
		interfaceConfigLoader.refreshConfigs();

		// 2. Clear XSD Cache
		xsdValidator.clearSchemaCache();

		// 3. Clear BeanIO Cache
		BeanIOFormatWriter.clearFactoryCache();

		return ResponseEntity.ok("Configuration and Caches reloaded successfully.");
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

		// 3. Trigger the Job immediately with these specific parameters
		// Note: We trigger the FILE_GEN_ADHOC_JOB which is capable of handling the specific type
		scheduler.triggerJob(JobKey.jobKey(FILE_GEN_ADHOC_JOB, FILE_GEN_GROUP), jobDataMap);

		logger.info("Admin manually triggered Quartz job for interface: {}", upperType);
		return ResponseEntity.ok("Manual trigger for " + upperType + " sent to Quartz scheduler.");
	}

	@GetMapping("/scheduler/jobs")
	public ResponseEntity<PagedResponse<Map<String, Object>>> listScheduledJobs(
			@RequestParam(value = "page", defaultValue = "0") int page,
			@RequestParam(value = "size", defaultValue = "10") int size) throws SchedulerException {

		List<Map<String, Object>> allJobs = new ArrayList<>();

		for (String groupName : scheduler.getJobGroupNames()) {
			for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName))) {
				List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);

				Map<String, Object> jobInfo = new HashMap<>();
				jobInfo.put("jobName", jobKey.getName());
				jobInfo.put("group", jobKey.getGroup());

				if (!triggers.isEmpty()) {
					Trigger trigger = triggers.get(0);
					jobInfo.put("nextFireTime", trigger.getNextFireTime());
					jobInfo.put("status", scheduler.getTriggerState(trigger.getKey()).name());
					if (trigger instanceof CronTrigger) {
						jobInfo.put("cronExpression", ((CronTrigger) trigger).getCronExpression());
					}
				}

				allJobs.add(jobInfo);
			}
		}

		// Sort by job name so pagination is deterministic
		allJobs.sort(Comparator.comparing(m -> (String) m.get("jobName")));
		return ResponseEntity.ok(paginate(allJobs, page, size));
	}

	@GetMapping("/scheduler/status")
	public ResponseEntity<PagedResponse<Map<String, Object>>> getDetailedJobStatus(
			@RequestParam(value = "page", defaultValue = "0") int page,
			@RequestParam(value = "size", defaultValue = "10") int size) throws SchedulerException {

		List<Map<String, Object>> scheduledJobs = new ArrayList<>();

		for (String groupName : scheduler.getJobGroupNames()) {
			for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName))) {
				JobDetail jobDetail = scheduler.getJobDetail(jobKey);
				List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);

				for (Trigger trigger : triggers) {
					Map<String, Object> info = new LinkedHashMap<>();
					String interfaceType = jobDetail.getJobDataMap().getString("interfaceType");

					info.put("jobName", jobKey.getName());
					info.put("interfaceType", interfaceType != null ? interfaceType : "SYSTEM_TASK");
					info.put("triggerName", trigger.getKey().getName());
					info.put("nextRunTime", trigger.getNextFireTime());
					info.put("previousRunTime", trigger.getPreviousFireTime());
					info.put("status", scheduler.getTriggerState(trigger.getKey()).name());

					if (trigger instanceof CronTrigger) {
						info.put("cronExpression", ((CronTrigger) trigger).getCronExpression());
					}

					scheduledJobs.add(info);
				}
			}
		}

		// Sort by nextRunTime (nulls last)
		scheduledJobs.sort(Comparator.comparing(m -> (Date) m.get("nextRunTime"), Comparator.nullsLast(Comparator.naturalOrder())));
		return ResponseEntity.ok(paginate(scheduledJobs, page, size));
	}

	/**
	 * Helper method to perform manual pagination on a List.
	 */
	private <T> PagedResponse<T> paginate(List<T> fullList, int page, int size) {
		int totalElements = fullList.size();
		int totalPages = (int) Math.ceil((double) totalElements / size);

		// Safeguard against out of bounds
		int start = Math.min(page * size, totalElements);
		int end = Math.min(start + size, totalElements);

		List<T> pagedContent = fullList.subList(start, end);

		PagedResponse<T> response = new PagedResponse<>();
		response.setContent(pagedContent);
		response.setPage(page);
		response.setSize(size);
		response.setTotalElements(totalElements);
		response.setTotalPages(totalPages);
		response.setLast(page >= totalPages - 1);

		return response;
	}
}
