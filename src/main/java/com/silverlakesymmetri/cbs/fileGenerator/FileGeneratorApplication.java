package com.silverlakesymmetri.cbs.fileGenerator;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for the CBS File Generator.
 *
 * <p>Architecture Overview:</p>
 * <ul>
 *   <li><b>Orchestration:</b> Quartz Scheduler (configured in {@code QuartzConfig}) triggers jobs.</li>
 *   <li><b>Execution:</b> Spring Batch processes data using Dynamic or Custom Readers/Writers.</li>
 *   <li><b>Concurrency:</b> Jobs are launched asynchronously via {@code BatchJobLauncher}.</li>
 * </ul>
 *
 * <p>Note on Annotations:</p>
 * <ul>
 *   <li>{@code @EnableBatchProcessing} is located in {@code BatchInfrastructureConfig}.</li>
 *   <li>{@code @EnableScheduling} is omitted because we use Quartz, not Spring's native scheduler.</li>
 * </ul>
 */
@SpringBootApplication
@EnableRetry           // Enables @Retryable (used in FileGenerationService for database locking recovery)
@EnableAsync           // Enables @Async (used in BatchJobLauncher for non-blocking execution)
@EnableScheduling      // REQUIRED: Enables @Scheduled in AppConfigService
@ComponentScan(basePackages = "com.silverlakesymmetri.cbs") // Ensures all sub-packages (config, batch, service) are scanned
public class FileGeneratorApplication {
	public static void main(String[] args) {
		SpringApplication.run(FileGeneratorApplication.class, args);
	}
}
