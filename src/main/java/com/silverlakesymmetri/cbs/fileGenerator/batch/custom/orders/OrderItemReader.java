package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.dto.order.OrderDto;
import com.silverlakesymmetri.cbs.fileGenerator.entity.order.Order;
import com.silverlakesymmetri.cbs.fileGenerator.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.NonTransientResourceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Component;

import javax.persistence.PersistenceException;
import javax.persistence.Tuple;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.ORDER_INTERFACE;

@Component
@StepScope
public class OrderItemReader implements ItemStreamReader<OrderDto> {
	private static final Logger logger = LoggerFactory.getLogger(OrderItemReader.class);
	private static final String CONTEXT_KEY_TOTAL = "order.reader.totalProcessed";
	private static final String CONTEXT_KEY_LAST_ID = "order.reader.lastProcessedId";

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
		if (pageSize <= 0) {
			throw new IllegalArgumentException("pageSize must be > 0");
		}
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
				logger.info("Processed {} records for interface {}", totalProcessed, ORDER_INTERFACE);
			}

			return orderDto;
		} catch (NonTransientResourceException e) {
			// Fatal database error – rethrow immediately
			logger.error("Non-transient resource failure while reading interface {}", ORDER_INTERFACE, e);
			throw e;
		} catch (PersistenceException e) {
			logger.error("Persistence error while reading interface {}", ORDER_INTERFACE, e);
			throw new NonTransientResourceException("Persistence error reading interface " + ORDER_INTERFACE, e);
		} catch (RuntimeException e) {
			// Programming or unexpected runtime issue
			logger.error("Unexpected runtime error while reading interface {}", ORDER_INTERFACE, e);
			throw e;
		} catch (Exception e) {
			// Truly unexpected checked exception
			logger.error("Unexpected checked exception while reading interface {}", ORDER_INTERFACE, e);
			throw new RuntimeException("Unexpected read failure", e);
		}
	}

	private Page<Order> loadNextPage() {
		try {
			Pageable pageable = new PageRequest(0, pageSize, Sort.Direction.ASC, "orderId");

			// STEP 1: Fetch only the IDs using Tuples (Extremely lightweight)
			Page<Tuple> idPage = (lastProcessedId == null)
					? orderRepository.findActiveIds(pageable)
					: orderRepository.findActiveIdsAfter(lastProcessedId, pageable);

			if (!idPage.hasContent()) {
				return new PageImpl<>(Collections.emptyList());
			}

			// STEP 2: Extract IDs
			List<Long> orderIds = idPage.getContent()
					.stream()
					.map(t -> t.get("id", Long.class))
					.collect(Collectors.toList());

			// STEP 3: Bulk Fetch details
			List<Order> ordersWithLines = orderRepository.findWithLineItemsByOrderIdIn(orderIds);

			// Sort to maintain Keyset sequence
			ordersWithLines.sort(Comparator.comparing(Order::getOrderId));

			logger.debug("Fetched {} full entities with line items. Last ID: {}",
					ordersWithLines.size(), lastProcessedId);

			return new PageImpl<>(ordersWithLines);

		} catch (Exception e) {
			logger.error("Error performing two-step fetch after ID {}", lastProcessedId, e);
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
			executionContext.put(CONTEXT_KEY_LAST_ID, lastProcessedId);
		}
	}

	@Override
	public void close() {
		// Cleanup resources like the resultIterator or temporary files
		this.resultIterator = null;
		logger.info("OrderItemReader closed. Total records read: {}", totalProcessed);
	}
}
