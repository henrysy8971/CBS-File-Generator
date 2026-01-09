package com.silverlakesymmetri.cbs.fileGenerator.batch.custom;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.constants.BatchMetricsConstants;
import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import com.silverlakesymmetri.cbs.fileGenerator.validation.XsdValidator;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@StepScope
public class OrderItemProcessor implements ItemProcessor<OrderDto, OrderDto> {
	private static final Logger logger = LoggerFactory.getLogger(OrderItemProcessor.class);
	private final InterfaceConfigLoader interfaceConfigLoader;
	private final XsdValidator xsdValidator;

	private StepExecution stepExecution;
	private String activeXsdSchema;

	@Autowired
	public OrderItemProcessor(
			InterfaceConfigLoader interfaceConfigLoader,
			@Autowired(required = false) XsdValidator xsdValidator) {
		this.interfaceConfigLoader = interfaceConfigLoader;
		this.xsdValidator = xsdValidator;
	}

	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.stepExecution = stepExecution;
		String interfaceType = stepExecution.getJobParameters().getString("interfaceType");

		// Initialize metrics
		initializeMetric(BatchMetricsConstants.KEY_PROCESSED);
		initializeMetric(BatchMetricsConstants.KEY_SKIPPED);
		initializeMetric(BatchMetricsConstants.KEY_INVALID);

		try {
			InterfaceConfig interfaceConfig = interfaceConfigLoader.getConfig(interfaceType);
			if (interfaceConfig != null) {
				String xsdSchemaFile = interfaceConfig.getXsdSchemaFile();
				if (xsdSchemaFile != null && xsdValidator != null && xsdValidator.schemaExists(xsdSchemaFile)) {
					this.activeXsdSchema = xsdSchemaFile;
					logger.info("XSD validation enabled for interface: {} (schema: {})",
							interfaceType, xsdSchemaFile);
				}
			}
		} catch (Exception e) {
			logger.warn("Metadata lookup failed for interface: {}. Proceeding without XSD validation.", interfaceType);
		}
	}

	@Override
	public OrderDto process(OrderDto orderDto) {
		try {
			if (!isValidOrder(orderDto)) {
				incrementMetric(BatchMetricsConstants.KEY_SKIPPED);
				logger.debug("Skipping empty record");
				return null;
			}

			if (!hasValidLineItems(orderDto)) {
				incrementMetric(BatchMetricsConstants.KEY_SKIPPED);
				return null;
			}

			applyTransformations(orderDto);

			incrementMetric(BatchMetricsConstants.KEY_PROCESSED);
			logger.debug("Record processed successfully Order: {}", orderDto.getOrderId());
			return orderDto;

		} catch (Exception e) {
			incrementMetric(BatchMetricsConstants.KEY_INVALID);
			logger.error("Critical error processing Order: {}", orderDto.getOrderId(), e);
			return null;
		}
	}

	// ---------------- Helpers ----------------
	private void applyTransformations(OrderDto order) {
		if (order.getOrderNumber() != null) order.setOrderNumber(order.getOrderNumber().trim());
		if (order.getCustomerName() != null) order.setCustomerName(order.getCustomerName().trim());

		if (order.getLineItems() != null) {
			order.getLineItems().forEach(item -> {
				if (item.getProductName() != null) item.setProductName(item.getProductName().trim());
			});
		}
	}

	// ================= Metrics =================
	private void initializeMetric(String key) {
		if (!stepExecution.getExecutionContext().containsKey(key)) {
			stepExecution.getExecutionContext().putLong(key, 0L);
		}
	}

	private void incrementMetric(String key) {
		if (stepExecution == null) {
			return;
		}
		ExecutionContext ctx = stepExecution.getExecutionContext();
		ctx.putLong(key, ctx.getLong(key, 0L) + 1);
	}

	// ================= Metrics Accessors =================
	public long getProcessedCount() {
		return stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_PROCESSED, 0L);
	}

	public long getSkippedCount() {
		return stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_SKIPPED, 0L);
	}

	public long getInvalidCount() {
		return stepExecution.getExecutionContext().getLong(BatchMetricsConstants.KEY_INVALID, 0L);
	}

	// ================= Validation =================
	private boolean isValidOrder(OrderDto order) {
		return order != null &&
				order.getOrderId() != null &&
				order.getOrderNumber() != null &&
				StringUtils.isNotBlank(order.getOrderNumber()) &&
				order.getOrderAmount() != null;
	}

	private boolean hasValidLineItems(OrderDto order) {
		if (order.getLineItems() == null || order.getLineItems().isEmpty()) {
			return true;
		}
		return order.getLineItems().stream()
				.anyMatch(item -> item.getLineItemId() != null &&
						item.getQuantity() != null &&
						item.getQuantity() > 0);
	}
}
