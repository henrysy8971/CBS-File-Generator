package com.silverlakesymmetri.cbs.fileGenerator.batch.custom;

import com.silverlakesymmetri.cbs.fileGenerator.batch.custom.OrderItemWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.listener.StepExecutionListenerSupport;

/**
 * Step execution listener for Order batch processing.
 * Captures order writer state (record count, file path) for job listener.
 */
public class OrderStepExecutionListener extends StepExecutionListenerSupport {

  private static final Logger logger = LoggerFactory.getLogger(OrderStepExecutionListener.class);

  private OrderItemWriter orderItemWriter;

  public OrderStepExecutionListener(OrderItemWriter orderItemWriter) {
    this.orderItemWriter = orderItemWriter;
  }

  /**
   * Before step: nothing to do, writer hasn't been initialized yet
   */
  @Override
  public void beforeStep(StepExecution stepExecution) {
    logger.debug("Order step execution started: {}", stepExecution.getStepName());
  }

  /**
   * After step: capture the part file path and record count in job execution context
   * This allows the job listener to finalize the file after job completes
   */
  @Override
  public ExitStatus afterStep(StepExecution stepExecution) {
    try {
      String partFilePath = orderItemWriter.getPartFilePath();

      if (partFilePath != null) {
        // Store in job execution context (accessible to job listener)
        stepExecution.getJobExecution().getExecutionContext()
            .put("partFilePath", partFilePath);

        // Store record count
        long recordCount = orderItemWriter.getRecordCount();
        stepExecution.getJobExecution().getExecutionContext()
            .put("recordCount", recordCount);

        logger.info("Order step execution completed. Part file path stored: {}, Record count: {}",
            partFilePath, recordCount);
      }
    } catch (Exception e) {
      logger.error("Error capturing part file path in order step listener", e);
    }

    return stepExecution.getExitStatus();
  }
}
