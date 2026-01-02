package com.silverlakesymmetri.cbs.fileGenerator.batch.custom;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.LineItemDto;
import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import com.silverlakesymmetri.cbs.fileGenerator.validation.XsdValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Order-specific item processor.
 * Validates orders and line items, applies transformations.
 * Optional XSD validation based on interface-config.json xsdSchemaFile setting.
 */
@Component
public class OrderItemProcessor implements ItemProcessor<OrderDto, OrderDto> {

	private static final Logger logger = LoggerFactory.getLogger(OrderItemProcessor.class);

	private static final String KEY_PROCESSED = "processedCount";
	private static final String KEY_SKIPPED = "skippedCount";
	private static final String KEY_INVALID = "invalidCount";

	@Autowired
	private InterfaceConfigLoader interfaceConfigLoader;

	@Autowired(required = false)
	private XsdValidator xsdValidator;

	private String xsdSchemaFile;
	private StepExecution stepExecution;

	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.stepExecution = stepExecution;
		String interfaceType = stepExecution.getJobParameters().getString("interfaceType");

		// Initialize counters in ExecutionContext if missing
		stepExecution.getExecutionContext().putLong(KEY_PROCESSED,
				stepExecution.getExecutionContext().getLong(KEY_PROCESSED, 0L));
		stepExecution.getExecutionContext().putLong(KEY_SKIPPED,
				stepExecution.getExecutionContext().getLong(KEY_SKIPPED, 0L));
		stepExecution.getExecutionContext().putLong(KEY_INVALID,
				stepExecution.getExecutionContext().getLong(KEY_INVALID, 0L));

		try {
			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
			if (config != null) {
				this.xsdSchemaFile = config.getXsdSchemaFile();
				if (xsdSchemaFile != null && xsdValidator != null && xsdValidator.schemaExists(xsdSchemaFile)) {
					logger.info("XSD schema validation enabled for ORDER_INTERFACE (schema: {})",
							xsdSchemaFile);
				}
			}
		} catch (Exception e) {
			logger.warn("Error loading schema configuration for interface: {}", interfaceType, e);
		}
	}

	@Override
	public OrderDto process(OrderDto orderDto) throws Exception {
		try {
			if (!validateOrder(orderDto)) {
				incrementCount(KEY_SKIPPED);
				logger.warn("Order validation failed: orderId={}", orderDto.getOrderId());
				return null;
			}

			int validLineItems = 0;
			for (LineItemDto lineItem : orderDto.getLineItems()) {
				if (validateLineItem(lineItem)) {
					validLineItems++;
				} else {
					logger.warn("Line item validation failed: lineItemId={}", lineItem.getLineItemId());
				}
			}

			if (validLineItems == 0 && !orderDto.getLineItems().isEmpty()) {
				incrementCount(KEY_SKIPPED);
				logger.warn("All line items failed validation for order: {}", orderDto.getOrderId());
				return null;
			}

			applyTransformations(orderDto);

			if (xsdSchemaFile != null && xsdValidator != null) {
				String xmlContent = convertOrderToXml(orderDto);
				if (!xsdValidator.validateRecord(xmlContent, xsdSchemaFile)) {
					incrementCount(KEY_INVALID);
					logger.warn("XSD validation failed for order: {} - skipping", orderDto.getOrderId());
					return null;
				}
			}

			incrementCount(KEY_PROCESSED);
			logger.debug("Order processed successfully: orderId={}, lineItems={}",
					orderDto.getOrderId(), validLineItems);
			return orderDto;

		} catch (Exception e) {
			incrementCount(KEY_INVALID);
			logger.error("Error processing order: orderId={}", orderDto.getOrderId(), e);
			return null;  // Skip on error
		}
	}

	/**
	 * Validate order data
	 */
	private boolean validateOrder(OrderDto orderDto) {
		// Check required fields
		if (orderDto.getOrderId() == null || orderDto.getOrderId().isEmpty()) {
			logger.warn("Order validation failed: orderId is empty");
			return false;
		}

		if (orderDto.getOrderNumber() == null || orderDto.getOrderNumber().isEmpty()) {
			logger.warn("Order validation failed: orderNumber is empty");
			return false;
		}

		if (orderDto.getOrderAmount() == null) {
			logger.warn("Order validation failed: orderAmount is null for orderId={}",
					orderDto.getOrderId());
			return false;
		}

		return true;
	}

	/**
	 * Validate line item data
	 */
	private boolean validateLineItem(LineItemDto lineItem) {
		if (lineItem.getLineItemId() == null || lineItem.getLineItemId().isEmpty()) {
			return false;
		}

		if (lineItem.getProductId() == null || lineItem.getProductId().isEmpty()) {
			return false;
		}

		return lineItem.getQuantity() != null && lineItem.getQuantity() > 0;
	}

	/**
	 * Apply transformations to order and line items
	 */
	private void applyTransformations(OrderDto orderDto) {
		// Trim string fields
		if (orderDto.getOrderNumber() != null) {
			orderDto.setOrderNumber(orderDto.getOrderNumber().trim());
		}

		if (orderDto.getCustomerName() != null) {
			orderDto.setCustomerName(orderDto.getCustomerName().trim());
		}

		// Transform line items
		for (LineItemDto lineItem : orderDto.getLineItems()) {
			if (lineItem.getProductName() != null) {
				lineItem.setProductName(lineItem.getProductName().trim());
			}
		}

		logger.debug("Transformations applied to order: {}", orderDto.getOrderId());
	}

	/**
	 * Convert Order to XML for validation
	 */
	private String convertOrderToXml(OrderDto orderDto) {
		StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		xml.append("<order>\n");
		xml.append("  <orderId>").append(escapeXml(orderDto.getOrderId())).append("</orderId>\n");
		xml.append("  <orderNumber>").append(escapeXml(orderDto.getOrderNumber())).append("</orderNumber>\n");

		if (orderDto.getOrderAmount() != null) {
			xml.append("  <orderAmount>").append(orderDto.getOrderAmount()).append("</orderAmount>\n");
		}

		if (orderDto.getCustomerName() != null) {
			xml.append("  <customerName>").append(escapeXml(orderDto.getCustomerName()))
					.append("</customerName>\n");
		}

		// Add line items
		if (!orderDto.getLineItems().isEmpty()) {
			xml.append("  <lineItems>\n");
			for (LineItemDto lineItem : orderDto.getLineItems()) {
				xml.append("    <lineItem>\n");
				xml.append("      <lineItemId>").append(escapeXml(lineItem.getLineItemId()))
						.append("</lineItemId>\n");
				xml.append("      <productId>").append(escapeXml(lineItem.getProductId()))
						.append("</productId>\n");
				if (lineItem.getQuantity() != null) {
					xml.append("      <quantity>").append(lineItem.getQuantity()).append("</quantity>\n");
				}
				xml.append("    </lineItem>\n");
			}
			xml.append("  </lineItems>\n");
		}

		xml.append("</order>\n");
		return xml.toString();
	}

	/**
	 * Escape XML special characters
	 */
	private String escapeXml(String text) {
		if (text == null) {
			return "";
		}
		return text
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&apos;");
	}

	private void incrementCount(String key) {
		long current = stepExecution.getExecutionContext().getLong(key, 0L);
		stepExecution.getExecutionContext().putLong(key, current + 1);
	}

	// ---------------- Public accessors for listeners ----------------

	public long getProcessedCount() {
		return stepExecution.getExecutionContext().getLong(KEY_PROCESSED, 0L);
	}

	public long getSkippedCount() {
		return stepExecution.getExecutionContext().getLong(KEY_SKIPPED, 0L);
	}

	public long getInvalidCount() {
		return stepExecution.getExecutionContext().getLong(KEY_INVALID, 0L);
	}
}
