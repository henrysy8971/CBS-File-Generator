package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.validator.ValidationException;
import org.springframework.stereotype.Component;
import org.springframework.batch.core.configuration.annotation.StepScope;

import java.util.Optional;

@Component
@StepScope
public class OrderItemProcessor implements ItemProcessor<OrderDto, OrderDto> {
	private static final Logger logger = LoggerFactory.getLogger(OrderItemProcessor.class);

	@Override
	public OrderDto process(OrderDto orderDto) throws Exception {
		if (orderDto == null) {
			return null;
		}

		try {
			// 1. Structural Validation
			validateOrderStructure(orderDto);

			// 2. Data Transformation (Sanitization)
			OrderDto transformed = applyTransformations(orderDto);

			logger.debug("Successfully processed Order ID: {}", transformed.getOrderId());
			return transformed;

		} catch (ValidationException ve) {
			// This will trigger the 'skip' logic defined in OrderBatchConfig
			logger.warn("Skipping record due to validation failure: {} - Reason: {}",
					orderDto.getOrderId(), ve.getMessage());
			throw ve;
		} catch (Exception e) {
			// General errors that might be transient or data-related
			logger.error("Unexpected error processing Order ID: {}", orderDto.getOrderId(), e);
			throw e;
		}
	}

	private void validateOrderStructure(OrderDto order) {
		if (order.getOrderId() == null) {
			throw new ValidationException("Missing Order ID");
		}
		if (StringUtils.isBlank(order.getOrderNumber())) {
			throw new ValidationException("Order Number is blank for ID: " + order.getOrderId());
		}
		if (order.getOrderAmount() == null) {
			throw new ValidationException("Missing Order Amount for ID: " + order.getOrderId());
		}

		// Complex business rule validation
		if (!hasValidLineItems(order)) {
			throw new ValidationException("No valid line items found for ID: " + order.getOrderId());
		}
	}

	private OrderDto applyTransformations(OrderDto order) {
		// Trim primary fields safely
		order.setOrderNumber(StringUtils.trim(order.getOrderNumber()));
		order.setCustomerName(StringUtils.trim(order.getCustomerName()));

		// Use Optional/Streams for cleaner line item processing
		Optional.ofNullable(order.getLineItems())
				.ifPresent(items -> items.forEach(item -> {
					item.setProductName(StringUtils.trim(item.getProductName()));
				}));

		return order;
	}

	private boolean hasValidLineItems(OrderDto order) {
		// If line items exist, at least one must be valid.
		// If the list is empty, we consider it valid (adjust based on bank rules)
		if (order.getLineItems() == null || order.getLineItems().isEmpty()) {
			return true;
		}
		return order.getLineItems().stream()
				.anyMatch(item -> item.getLineItemId() != null &&
						item.getQuantity() != null &&
						item.getQuantity() > 0);
	}
}
