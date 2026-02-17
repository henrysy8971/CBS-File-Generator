package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.dto.order.LineItemDto;
import com.silverlakesymmetri.cbs.fileGenerator.dto.order.OrderDto;
import com.silverlakesymmetri.cbs.fileGenerator.entity.order.LineItem;
import com.silverlakesymmetri.cbs.fileGenerator.entity.order.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class OrderRowMapper {
	private static final Logger logger = LoggerFactory.getLogger(OrderRowMapper.class);
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/**
	 * Map Order JPA entity to OrderDto (includes line items)
	 */
	public OrderDto mapRow(Order order) {
		if (order == null) return null;

		try {
			OrderDto dto = new OrderDto();

			// Map parent fields
			dto.setOrderId(order.getOrderId());
			dto.setOrderNumber(order.getOrderNumber());
			dto.setOrderAmount(order.getOrderAmount());
			dto.setCustomerId(order.getCustomerId());
			dto.setCustomerName(order.getCustomerName());
			dto.setStatus(order.getStatus());

			// Convert timestamp to string
			if (order.getOrderDate() != null) {
				dto.setOrderDate(order.getOrderDate().toLocalDateTime().format(DATE_TIME_FORMATTER));
			}

			// Map child line items
			if (order.getLineItems() != null && !order.getLineItems().isEmpty()) {
				for (LineItem lineItem : order.getLineItems()) {
					LineItemDto itemDto = mapLineItem(lineItem);
					dto.addLineItem(itemDto);
				}
				logger.debug("Mapped order {} with {} line items",
						order.getOrderId(), order.getLineItems().size());
			}

			return dto;
		} catch (Exception e) {
			logger.error("Error mapping order entity to DTO for orderId: {}", order.getOrderId(), e);
			throw new RuntimeException("Error mapping order data", e);
		}
	}

	/**
	 * Map LineItem JPA entity to LineItemDto
	 */
	private LineItemDto mapLineItem(LineItem lineItem) {
		LineItemDto dto = new LineItemDto();

		dto.setLineItemId(lineItem.getLineItemId());
		dto.setOrderId(lineItem.getOrderId());
		dto.setProductId(lineItem.getProductId());
		dto.setProductName(lineItem.getProductName());
		dto.setQuantity(lineItem.getQuantity());
		dto.setUnitPrice(lineItem.getUnitPrice());
		dto.setLineAmount(lineItem.getLineAmount());
		dto.setStatus(lineItem.getStatus());

		return dto;
	}
}
