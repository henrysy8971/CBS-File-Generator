package com.silverlakesymmetri.cbs.fileGenerator.scheduler;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.service.BatchJobLauncher;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.QUARTZ_BATCH_JOB_USER_NAME_DEFAULT;

@Component
@DisallowConcurrentExecution
public class BatchJobLauncherJob extends QuartzJobBean {
	private static final Logger logger = LoggerFactory.getLogger(BatchJobLauncherJob.class);

	private final BatchJobLauncher batchJobLauncher;
	private final FileGenerationService fileGenerationService;
	private final InterfaceConfigLoader interfaceConfigLoader;
	private final String outputDir;

	public BatchJobLauncherJob(
			BatchJobLauncher batchJobLauncher,
			FileGenerationService fileGenerationService,
			InterfaceConfigLoader interfaceConfigLoader,
			@Value("${file.generation.output-directory}") String outputDir
	) {
		this.batchJobLauncher = batchJobLauncher;
		this.fileGenerationService = fileGenerationService;
		this.interfaceConfigLoader = interfaceConfigLoader;
		this.outputDir = outputDir;
	}

	@Override
	protected void executeInternal(JobExecutionContext jobExecutionContext) throws JobExecutionException {
		JobDataMap dataMap = jobExecutionContext.getMergedJobDataMap();
		String interfaceType = dataMap.getString("interfaceType");

		// Generate Idempotency Key
		// Use Scheduled Fire Time so if Quartz misfires or retries,
		// it generates the exact same key.
		long fireTime = jobExecutionContext.getScheduledFireTime().getTime();
		String idempotencyKey = interfaceType + "_SCHED_" + fireTime;

		String requestId = "SCHED-" + UUID.randomUUID();

		logger.info("Quartz triggering scheduled generation for: {}", interfaceType);

		try {
			// Fetch config to get the correct extension (csv, xml, txt)
			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
			String ext = config.getOutputFileExtension() != null ? config.getOutputFileExtension() : "txt";

			// 1. Create a tracking record in the database
			String fileName = interfaceType + "_" + UUID.randomUUID() + "." + ext;

			FileGeneration fileGen = fileGenerationService.createFileGeneration(
					fileName,
					outputDir,
					QUARTZ_BATCH_JOB_USER_NAME_DEFAULT,
					interfaceType,
					idempotencyKey
			);

			// Immediately mark as QUEUED.
			// This prevents the Poller from "stealing" this job while we are preparing to launch it.
			fileGenerationService.markQueued(fileGen.getJobId());

			// 2. Launch the Spring Batch Job
			batchJobLauncher.launchFileGenerationJob(fileGen.getJobId(), interfaceType, requestId);
		} catch (Exception e) {
			logger.error("Failed to launch scheduled job for {}", interfaceType, e);
			throw new JobExecutionException(e);
		}
	}
}
