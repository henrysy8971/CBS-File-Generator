package com.silverlakesymmetri.cbs.fileGenerator.scheduler;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.service.BatchJobLauncher;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@DisallowConcurrentExecution
public class BatchJobLauncherJob implements Job {
	private static final Logger logger = LoggerFactory.getLogger(BatchJobLauncherJob.class);

	@Autowired
	private BatchJobLauncher batchJobLauncher;

	@Autowired
	private FileGenerationService fileGenerationService;

	@Autowired
	private InterfaceConfigLoader interfaceConfigLoader;

	@Value("${file.generation.output-directory}")
	private String outputDir;

	@Override
	public void execute(JobExecutionContext context) throws JobExecutionException {
		JobDataMap dataMap = context.getMergedJobDataMap();
		String interfaceType = dataMap.getString("interfaceType");

		logger.info("Quartz triggering scheduled generation for: {}", interfaceType);

		try {
			// Fetch config to get the correct extension (csv, xml, txt)
			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
			String ext = config.getOutputFileExtension() != null ? config.getOutputFileExtension() : "txt";

			// 1. Create a tracking record in the database
			String fileName = interfaceType + "_" + UUID.randomUUID() + "." + ext;

			FileGeneration fileGen = fileGenerationService.createFileGeneration(
					fileName, outputDir, "QUARTZ_SCHEDULER", interfaceType
			);

			// 2. Launch the Spring Batch Job
			batchJobLauncher.launchFileGenerationJob(fileGen.getJobId(), interfaceType);
		} catch (Exception e) {
			logger.error("Failed to launch scheduled job for {}", interfaceType, e);
			throw new JobExecutionException(e);
		}
	}
}
