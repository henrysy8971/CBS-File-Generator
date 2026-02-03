package com.silverlakesymmetri.cbs.fileGenerator.scheduler;

import com.silverlakesymmetri.cbs.fileGenerator.service.BatchJobLauncher;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * The background worker that polls the database for PENDING file requests.
 */
@Component
@DisallowConcurrentExecution
public class FileGenerationScheduler extends QuartzJobBean {
	private static final Logger logger = LoggerFactory.getLogger(FileGenerationScheduler.class);
	private final FileGenerationService fileGenerationService;
	private final BatchJobLauncher batchJobLauncher;

	@Autowired
	public FileGenerationScheduler(FileGenerationService fileGenerationService, BatchJobLauncher batchJobLauncher) {
		this.fileGenerationService = fileGenerationService;
		this.batchJobLauncher = batchJobLauncher;
	}

	@Override
	protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
		logger.debug("Quartz Poller: Checking for pending file generation requests...");

		try {
			// 1. Fetch pending requests from DB
			fileGenerationService.getPendingFileGenerations().forEach(fileGen -> {
				try {
					logger.info("Poller claiming request: {} for interface: {}", fileGen.getJobId(), fileGen.getInterfaceType());

					batchJobLauncher.launchFileGenerationJob(
							fileGen.getJobId(),
							fileGen.getInterfaceType()
					);
				} catch (Exception e) {
					logger.error("Failed to hand off JobId {} to Batch Launcher", fileGen.getJobId(), e);
				}
			});
		} catch (Exception e) {
			// Don't crash the whole scheduler, just log the DB connectivity issue
			logger.error("Poller could not retrieve pending jobs from database", e);
		}
	}
}
