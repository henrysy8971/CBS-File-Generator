package com.silverlakesymmetri.cbs.fileGenerator.batch.custom;

import com.silverlakesymmetri.cbs.fileGenerator.batch.DynamicJobExecutionListener;
import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileFinalizationService;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Batch configuration for ORDER_INTERFACE file generation.
 * Specialized configuration for Order processing with eager loading of line items.
 */
@Configuration
public class OrderBatchConfig {

  private static final Logger logger = LoggerFactory.getLogger(OrderBatchConfig.class);

  @Autowired
  private JobBuilderFactory jobBuilderFactory;

  @Autowired
  private StepBuilderFactory stepBuilderFactory;

  @Autowired
  private OrderItemReader orderItemReader;

  @Autowired
  private OrderItemProcessor orderItemProcessor;

  @Autowired
  private OrderItemWriter orderItemWriter;

  @Autowired
  private FileGenerationService fileGenerationService;

  @Autowired
  private FileFinalizationService fileFinalizationService;

  /**
   * Define batch job for order file generation.
   * Uses pagination-based reading to handle large datasets efficiently.
   */
  @Bean
  public Job orderFileGenerationJob() {
    logger.info("Configuring orderFileGenerationJob");
    return jobBuilderFactory.get("orderFileGenerationJob")
        .incrementer(new RunIdIncrementer())
        .listener(new DynamicJobExecutionListener(fileGenerationService, fileFinalizationService))
        .flow(orderFileGenerationStep())
        .end()
        .build();
  }

  /**
   * Define step for order file generation.
   * Configures reader, processor, and writer for Order processing.
   */
  @Bean
  public Step orderFileGenerationStep() {
    logger.info("Configuring orderFileGenerationStep");
    return stepBuilderFactory.get("orderFileGenerationStep")
        .<OrderDto, OrderDto>chunk(1000)
        .reader(orderItemReader)
        .processor(orderItemProcessor)
        .writer(items -> {
          try {
            orderItemWriter.write(items);
          } catch (Exception e) {
            logger.error("Error writing order items", e);
            throw new RuntimeException("Error writing order items", e);
          }
        })
        .listener(new OrderStepExecutionListener(orderItemWriter))
        .build();
  }
}
