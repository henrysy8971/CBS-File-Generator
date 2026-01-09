package com.silverlakesymmetri.cbs.fileGenerator.service;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class BatchJobLauncher {
	private static final Logger logger = LoggerFactory.getLogger(BatchJobLauncher.class);
	private final JobLauncher jobLauncher;
	private final Job defaultJob;
	private final InterfaceConfigLoader interfaceConfigLoader;
	private final FileGenerationService fileGenerationService;
	private final Map<String, Job> allJobs;

	@Value("${file.generation.output-directory}")
	private String outputDirectory;

	@Autowired
	public BatchJobLauncher(JobLauncher jobLauncher,
							@Qualifier("dynamicFileGenerationJob") Job defaultJob, // Explicitly pick the generic job
							InterfaceConfigLoader interfaceConfigLoader,
							FileGenerationService fileGenerationService,
							Map<String, Job> allJobs) {
		this.jobLauncher = jobLauncher;
		this.defaultJob = defaultJob;
		this.interfaceConfigLoader = interfaceConfigLoader;
		this.fileGenerationService = fileGenerationService;
		this.allJobs = allJobs;
	}

	@Async
	public CompletableFuture<Void> launchFileGenerationJob(String jobId, String interfaceType) {
		try {
			Optional<FileGeneration> currentJob = fileGenerationService.getFileGeneration(jobId);

			if (!currentJob.isPresent()) {
				logger.error("Job launch aborted: JobId {} not found in database", jobId);
				return CompletableFuture.completedFuture(null);
			}

			// Only allow PENDING jobs to start.
			// This prevents double-clicks from triggering multiple Batch runs.
			if (!FileGenerationStatus.PENDING.name().equals(currentJob.get().getStatus())) {
				logger.warn("Job launch aborted: JobId {} is already in status {}",
						jobId, currentJob.get().getStatus());
				return CompletableFuture.completedFuture(null);
			}

			// Validate interface configuration
			if (!interfaceConfigLoader.interfaceExists(interfaceType)) {
				String error = "Interface configuration not found: " + interfaceType;
				logger.error("Job launch failed: {}", error);
				fileGenerationService.markFailed(jobId, error);
				return CompletableFuture.completedFuture(null);
			}

			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
			// Determine file extension, defaulting to 'txt'
			String extension = Optional.ofNullable(config.getOutputFileExtension()).orElse("txt");
			// Build output file path with .part during processing
			String outputFilePath = buildOutputFilePath(jobId, interfaceType, extension);

			// Build job parameters
			JobParameters jobParameters = new JobParametersBuilder()
					.addString("jobId", jobId)
					.addString("interfaceType", interfaceType)
					.addString("outputFilePath", outputFilePath)
					.addLong("timestamp", System.currentTimeMillis())
					.toJobParameters();

			logger.info("Launching file generation job - jobId: {}, interfaceType: {}, outputPath: {}",
					jobId, interfaceType, outputFilePath);

			// Select appropriate job (specialized or dynamic)
			Job jobToRun = selectJobByInterfaceType(interfaceType);

			logger.info("Starting Batch Job [{}] for interface [{}]", jobToRun.getName(), interfaceType);

			fileGenerationService.markProcessing(jobId); // Update status to IN_PROGRESS before launch

			// Launch the job
			jobLauncher.run(jobToRun, jobParameters);
		} catch (Exception e) {
			logger.error("Critical failure launching Batch Job {}", jobId, e);
			fileGenerationService.markFailed(jobId, "Launch Failure: " + e.getMessage());
		}

		return CompletableFuture.completedFuture(null);
	}

	/**
	 * Select the batch job for the given interfaceType.
	 * Falls back to dynamicFileGenerationJob if no specialized job is configured.
	 */
	private Job selectJobByInterfaceType(String interfaceType) {
		// 1. Try exact match (Bean name matches interface name)
		if (allJobs.containsKey(interfaceType)) {
			return allJobs.get(interfaceType);
		}

		// 2. Try Case-Insensitive match
		for (Map.Entry<String, Job> entry : allJobs.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(interfaceType)) {
				return entry.getValue();
			}
		}

		return defaultJob;
	}

	/**
	 * Builds a safe output file path with .part extension for in-progress files.
	 */
	private String buildOutputFilePath(String jobId, String interfaceType, String extension) {
		return Paths.get(outputDirectory, interfaceType + "_" + jobId + "." + extension + ".part")
				.toAbsolutePath().toString();
	}
}
