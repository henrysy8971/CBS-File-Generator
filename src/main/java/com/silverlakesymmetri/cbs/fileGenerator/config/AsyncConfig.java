package com.silverlakesymmetri.cbs.fileGenerator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

	@Value("${spring.task.execution.pool.core-size:5}")
	private int corePoolSize;

	@Value("${spring.task.execution.pool.max-size:10}")
	private int maxPoolSize;

	@Value("${spring.task.execution.pool.queue-capacity:100}")
	private int queueCapacity;

	@Value("${spring.task.execution.thread-name-prefix:async-task-}")
	private String threadNamePrefix;

	/**
	 * This bean replaces the auto-configuration missing in Spring Boot 1.5.x.
	 * It handles asynchronous tasks like triggering the FileFinalizationService
	 * or sending email alerts without blocking the main Batch thread.
	 */
	@Bean(name = "taskExecutor")
	public Executor taskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(corePoolSize);
		executor.setMaxPoolSize(maxPoolSize);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix(threadNamePrefix);

		// Critical for Banking: Ensure threads wait for tasks to finish on shutdown
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);

		executor.initialize();
		return executor;
	}
}
