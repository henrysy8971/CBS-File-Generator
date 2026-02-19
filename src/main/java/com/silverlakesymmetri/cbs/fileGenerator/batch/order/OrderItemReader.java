package com.silverlakesymmetri.cbs.fileGenerator.batch.order;

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
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.PersistenceException;
import javax.persistence.Tuple;
import java.util.*;
import java.util.stream.Collectors;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.ORDER_INTERFACE;

@Component
@StepScope
@Transactional(readOnly = true)
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
		this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
		this.orderRowMapper = Objects.requireNonNull(orderRowMapper, "orderRowMapper must not be null");

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
				List<Order> nextBatch = loadNextPage();
				if (nextBatch.isEmpty()) {
					logger.info("End of data reached. Total records processed={}", totalProcessed);
					return null;
				}
				resultIterator = nextBatch.iterator();
			}

			// Map next Order to DTO
			Order order = resultIterator.next();

			if (order == null) return null;

			OrderDto orderDto = Objects.requireNonNull(
					orderRowMapper.mapRow(order),
					"OrderRowMapper returned null for orderId=" + order.getOrderId()
			);

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
		}
	}

	private List<Order> loadNextPage() {
		try {
			Pageable pageable = new PageRequest(0, pageSize, Sort.Direction.ASC, "orderId");

			// STEP 1: Fetch only the IDs using Tuples (Extremely lightweight)
			Slice<Tuple> idSlice = (lastProcessedId == null)
					? orderRepository.findActiveIds("ACTIVE", pageable)
					: orderRepository.findActiveIdsAfter("ACTIVE", lastProcessedId, pageable);

			if (!idSlice.hasContent()) {
				return Collections.emptyList();
			}

			// STEP 2: Extract IDs
			List<Tuple> tuples = idSlice.getContent();
			List<Long> orderIds = new ArrayList<>(tuples.size());

			for (Tuple tuple : tuples) {
				Long id = tuple.get("id", Long.class);
				if (id != null) {
					orderIds.add(id);
				}
			}

			if (orderIds.isEmpty()) {
				return Collections.emptyList();
			}

			// STEP 3: Bulk Fetch details
			List<Order> ordersWithLines = orderRepository.findWithLineItemsByOrderIdIn(orderIds);

			if (ordersWithLines.isEmpty()) {
				return Collections.emptyList();
			}

			// Preserve strict ordering based on key set sequence
			Map<Long, Order> orderMap = new HashMap<>(ordersWithLines.size());

			for (Order order : ordersWithLines) {
				Long orderId = order.getOrderId();

				if (orderMap.containsKey(orderId)) {
					logger.warn("Duplicate orderId detected: {}", orderId);
					continue; // keep existing (same behavior as merge function)
				}

				orderMap.put(orderId, order);
			}

			logger.debug("Fetched {} full entities with line items. Last ID: {}",
					ordersWithLines.size(), lastProcessedId);

			List<Order> orderedOrders = new ArrayList<>(orderIds.size());

			for (Long orderId : orderIds) {
				Order order = orderMap.get(orderId);
				if (order != null) {
					orderedOrders.add(order);
				}
			}

			return orderedOrders;

		} catch (PersistenceException e) {
			logger.error("Persistence error performing two-step fetch after ID {}", lastProcessedId, e);
			throw e;
		}
	}

	@Override
	public void open(ExecutionContext executionContext) {
		totalProcessed = executionContext.getLong(CONTEXT_KEY_TOTAL, 0L);
		lastProcessedId = executionContext.containsKey(CONTEXT_KEY_LAST_ID)
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
		resultIterator = null;
		logger.info("OrderItemReader closed. Total records read: {}", totalProcessed);
	}
}
