package com.silverlakesymmetri.cbs.fileGenerator.scheduler;

import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.service.BatchJobLauncherService;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The background worker that polls the database for PENDING file requests.
 */
@Component
@DisallowConcurrentExecution
public class FileGenerationScheduler extends QuartzJobBean {
	private static final Logger logger = LoggerFactory.getLogger(FileGenerationScheduler.class);
	private final FileGenerationService fileGenerationService;
	private final BatchJobLauncherService batchJobLauncherService;

	public FileGenerationScheduler(FileGenerationService fileGenerationService, BatchJobLauncherService batchJobLauncherService) {
		this.fileGenerationService = fileGenerationService;
		this.batchJobLauncherService = batchJobLauncherService;
	}

	@Override
	protected void executeInternal(JobExecutionContext context) {
		logger.debug("Quartz Poller: Checking for pending file generation requests...");

		try {
			// 1. Get PENDING jobs
			List<FileGeneration> pendingJobs = fileGenerationService.getPendingFileGenerations();

			for (FileGeneration fileGen : pendingJobs) {
				try {
					// 2. Try to CLAIM the job by moving it to QUEUED
					// If another node picked this up 1ms ago, this will throw an exception
					fileGenerationService.markQueued(fileGen.getJobId());

					// 3. If we are here, we successfully claimed it. Now Launch.
					String requestId = "POLLER-" + UUID.randomUUID();

					logger.info("Poller claiming request: {} for interface: {}", fileGen.getJobId(), fileGen.getInterfaceType());
					// Pass requestId to the updated signature we discussed
					batchJobLauncherService.launchFileGenerationJob(
							fileGen.getJobId(),
							fileGen.getInterfaceType(),
							requestId
					);
				} catch (Exception e) {
					logger.error("Failed to hand off JobId {} to Batch Launcher", fileGen.getJobId(), e);
				}
			}
		} catch (Exception e) {
			// Don't crash the whole scheduler, just log the DB connectivity issue
			logger.error("Poller could not retrieve pending jobs from database", e);
		}
	}
}
