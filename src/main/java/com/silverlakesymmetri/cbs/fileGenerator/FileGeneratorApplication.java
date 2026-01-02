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
 * * Features:
 * - @EnableRetry: Supports the database update retry policy.
 * - @EnableAsync: Allows the BatchJobLauncher to launch jobs in separate threads.
 * - @EnableBatchProcessing: Initializes the Spring Batch infrastructure.
 */
@SpringBootApplication
@EnableRetry           // Required for @Retryable in Service layer
@EnableAsync           // Required for @Async in Launcher
@EnableBatchProcessing // Required for DynamicBatchConfig
@EnableScheduling
@ComponentScan(basePackages = "com.silverlakesymmetri.cbs")
public class FileGeneratorApplication {
	public static void main(String[] args) {
		SpringApplication.run(FileGeneratorApplication.class, args);
	}
}
