package com.silverlakesymmetri.cbs.fileGenerator.batch.custom;

import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import com.silverlakesymmetri.cbs.fileGenerator.entity.Order;
import com.silverlakesymmetri.cbs.fileGenerator.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

@Component
@StepScope
public class OrderItemReader implements ItemStreamReader<OrderDto> {
	private static final Logger logger = LoggerFactory.getLogger(OrderItemReader.class);

	private static final String CONTEXT_KEY_TOTAL = "order.reader.totalProcessed";
	private static final String CONTEXT_KEY_LAST_ID = "order.reader.lastProcessedId";
	private static final String INTERFACE_TYPE = "ORDER_INTERFACE";

	private final int pageSize;
	private final OrderRepository orderRepository;
	private final OrderRowMapper orderRowMapper;

	private Iterator<Order> resultIterator;
	private Long lastProcessedId;
	private long totalProcessed;

	public OrderItemReader(
			OrderRepository orderRepository,
			OrderRowMapper orderRowMapper,
			@Value("${file.generation.chunk-size:1000}") int pageSize
	) {
		this.orderRepository = orderRepository;
		this.orderRowMapper = orderRowMapper;
		this.pageSize = pageSize;
	}

	@Override
	public OrderDto read() {
		try {
			// Load next page if iterator is empty
			if (resultIterator == null || !resultIterator.hasNext()) {
				Page<Order> page = loadNextPage();
				if (!page.hasContent()) {
					logger.info("End of data reached. Total records processed={}", totalProcessed);
					return null;
				}
				resultIterator = page.getContent().iterator();
			}

			// Map next Order to DTO
			Order order = resultIterator.next();
			OrderDto orderDto = orderRowMapper.mapRow(order);

			lastProcessedId = orderDto.getOrderId();
			totalProcessed++;

			if (totalProcessed % pageSize == 0) {
				logger.info("Processed {} records for interface {}", totalProcessed, INTERFACE_TYPE);
			}

			return orderDto;
		} catch (Exception e) {
			logger.error("Error reading record for interface {}", INTERFACE_TYPE, e);
			throw new RuntimeException(e);
		}
	}

	private Page<Order> loadNextPage() {
		try {
			Pageable pageable = new PageRequest(
					0,
					pageSize,
					Sort.Direction.ASC,
					"orderId"
			);

			// STEP 1: Fetch page of Orders (IDs only)
			Page<Order> page = (lastProcessedId == null)
					? orderRepository.findFirstActive(pageable)
					: orderRepository.findActiveAfterId(lastProcessedId, pageable);

			if (!page.hasContent()) {
				return page;
			}

			// STEP 2: Fetch line items in bulk
			List<Long> orderIds = page.getContent()
					.stream()
					.map(Order::getOrderId)
					.collect(Collectors.toList());

			List<Order> ordersWithLines = orderIds.isEmpty()
					? Collections.emptyList()
					: orderRepository.findWithLineItemsByOrderIdIn(orderIds);

			// Sort in-memory to preserve order
			ordersWithLines.sort(Comparator.comparing(Order::getOrderId));

			logger.debug(
					"Loaded {} orders with line items after lastProcessedId={}",
					ordersWithLines.size(),
					lastProcessedId
			);

			// IMPORTANT:
			// Return a Page that contains the fully-loaded Orders
			return new PageImpl<>(ordersWithLines);

		} catch (Exception e) {
			logger.error("Error loading orders after id {}", lastProcessedId, e);
			throw new RuntimeException("Error reading orders from database", e);
		}
	}

	@Override
	public void open(ExecutionContext executionContext) {
		this.totalProcessed = executionContext.getLong(CONTEXT_KEY_TOTAL, 0L);
		this.lastProcessedId = executionContext.containsKey(CONTEXT_KEY_LAST_ID)
				? executionContext.getLong(CONTEXT_KEY_LAST_ID)
				: null;

		logger.info(
				"Opening OrderItemReader. Restart={}, lastProcessedId={}, totalProcessed={}",
				executionContext.containsKey(CONTEXT_KEY_LAST_ID),
				lastProcessedId,
				totalProcessed
		);
	}

	@Override
	public void update(ExecutionContext executionContext) {
		executionContext.putLong(CONTEXT_KEY_TOTAL, totalProcessed);
		if (lastProcessedId != null) {
			executionContext.putLong(CONTEXT_KEY_LAST_ID, lastProcessedId);
		}
	}

	@Override
	public void close() {
		// Cleanup resources like the resultIterator or temporary files
		this.resultIterator = null;
		logger.info("DynamicItemReader closed. Total records read: {}", totalProcessed);
	}
}
