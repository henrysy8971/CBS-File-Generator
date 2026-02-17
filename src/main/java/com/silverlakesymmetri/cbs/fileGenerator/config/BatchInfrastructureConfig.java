package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.silverlakesymmetri.cbs.fileGenerator.batch.DynamicJobExecutionListener;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileFinalizationService;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableBatchProcessing
public class BatchInfrastructureConfig {

	private static final Logger logger = LoggerFactory.getLogger(BatchInfrastructureConfig.class);

	@Value("${spring.task.execution.pool.core-size:5}")
	private int corePoolSize;

	@Value("${spring.task.execution.pool.max-size:10}")
	private int maxPoolSize;

	@Value("${spring.task.execution.pool.queue-capacity:100}")
	private int queueCapacity;

	@Value("${spring.task.execution.thread-name-prefix:batch-exec-}")
	private String threadNamePrefix;

	@Value("${spring.batch.table-prefix:BATCH_}")
	private String tablePrefix;

	@PostConstruct
	public void logConfig() {
		logger.info(
				"Batch infra initialized: tablePrefix={}, core={}, max={}, queue={}",
				tablePrefix, corePoolSize, maxPoolSize, queueCapacity
		);
	}

	@Bean
	@Primary // Ensure this one is used by the BatchJobLauncherService
	public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
		SimpleJobLauncher jobLauncher = new SimpleJobLauncher();
		jobLauncher.setJobRepository(jobRepository);
		jobLauncher.setTaskExecutor(batchTaskExecutor());
		jobLauncher.afterPropertiesSet();
		return jobLauncher;
	}

	@Bean
	public TaskExecutor batchTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(corePoolSize);
		executor.setMaxPoolSize(maxPoolSize);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix(threadNamePrefix);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.initialize();
		return executor;
	}

	@Bean
	public DynamicJobExecutionListener sharedJobListener(
			FileFinalizationService finalizationService,
			FileGenerationService generationService) {
		return new DynamicJobExecutionListener(finalizationService, generationService);
	}

	@Bean
	public JobRepository jobRepository(DataSource dataSource, PlatformTransactionManager tx) throws Exception {
		JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
		factory.setDataSource(dataSource);
		factory.setTransactionManager(tx);
		factory.setTablePrefix(tablePrefix);
		factory.setIsolationLevelForCreate("ISOLATION_READ_COMMITTED");
		factory.afterPropertiesSet();
		return factory.getObject();
	}
}
