package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.dto.LineItemDto;
import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.validator.ValidationException;
import org.springframework.stereotype.Component;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;

@Component
@StepScope
public class OrderItemProcessor implements ItemProcessor<OrderDto, OrderDto> {
	private static final Logger logger = LoggerFactory.getLogger(OrderItemProcessor.class);

	@Override
	public OrderDto process(OrderDto orderDto) {
		if (orderDto == null) {
			throw new IllegalStateException("Reader returned null OrderDto unexpectedly");
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
			// Catastrophic or unexpected error
			logger.error("Critical error processing Order ID: {}", orderDto.getOrderId(), e);
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

	private OrderDto applyTransformations(OrderDto source) {
		OrderDto target = new OrderDto();
		// Trim primary fields safely
		target.setOrderId(source.getOrderId());
		target.setOrderNumber(StringUtils.trim(source.getOrderNumber()));
		target.setCustomerName(StringUtils.trim(source.getCustomerName()));
		target.setOrderAmount(source.getOrderAmount());
		if (!CollectionUtils.isEmpty(source.getLineItems())) {
			List<LineItemDto> copied = source.getLineItems().stream()
					.map(item -> {
						LineItemDto li = new LineItemDto();
						li.setLineItemId(item.getLineItemId());
						li.setProductName(StringUtils.trim(item.getProductName()));
						li.setQuantity(item.getQuantity());
						return li;
					})
					.collect(Collectors.toList());
			target.setLineItems(copied);
		}

		return target;
	}

	private boolean hasValidLineItems(OrderDto order) {
		if (CollectionUtils.isEmpty(order.getLineItems())) {
			return false;
		}
		return order.getLineItems().stream()
				.anyMatch(item -> item.getLineItemId() != null &&
						item.getQuantity() != null &&
						item.getQuantity() > 0);
	}
}
