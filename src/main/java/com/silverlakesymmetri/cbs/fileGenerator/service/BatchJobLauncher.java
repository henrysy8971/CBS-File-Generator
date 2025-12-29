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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class BatchJobLauncher {

	private static final Logger logger = LoggerFactory.getLogger(BatchJobLauncher.class);

	private final JobLauncher jobLauncher;
	private final Job dynamicFileGenerationJob;
	private final InterfaceConfigLoader interfaceConfigLoader;
	private final FileGenerationService fileGenerationService;
	private final Map<String, Job> specializedJobs; // Keyed by interfaceType

	@Value("${file.generation.output-directory:./generated-files/}")
	private String outputDirectory;

	@Autowired
	public BatchJobLauncher(JobLauncher jobLauncher,
							Job dynamicFileGenerationJob,
							InterfaceConfigLoader interfaceConfigLoader,
							FileGenerationService fileGenerationService,
							Map<String, Job> specializedJobs) {
		this.jobLauncher = jobLauncher;
		this.dynamicFileGenerationJob = dynamicFileGenerationJob;
		this.interfaceConfigLoader = interfaceConfigLoader;
		this.fileGenerationService = fileGenerationService;
		this.specializedJobs = specializedJobs;
	}

	/**
	 * Launch file generation job asynchronously.
	 */
	@Async
	public CompletableFuture<Void> launchFileGenerationJob(String jobId, String interfaceType) {
		try {
			// Check current status before launching
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
			String extension = (config.getOutputFileExtension() != null && !config.getOutputFileExtension().isEmpty())
					? config.getOutputFileExtension() : "txt";

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

			logger.info("Starting Batch Job for JobId: {}", jobId);
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
		if (specializedJobs != null && specializedJobs.containsKey(interfaceType)) {
			logger.info("Using specialized job for interfaceType: {}", interfaceType);
			return specializedJobs.get(interfaceType);
		}
		logger.info("Using dynamicFileGenerationJob for interfaceType: {}", interfaceType);
		return dynamicFileGenerationJob;
	}

	/**
	 * Builds a safe output file path with .part extension for in-progress files.
	 */
	private String buildOutputFilePath(String jobId, String interfaceType, String extension) {
		Path fullPath = Paths.get(outputDirectory, interfaceType + "_" + jobId + "." + extension + ".part");
		return fullPath.toAbsolutePath().toString();
	}
}
