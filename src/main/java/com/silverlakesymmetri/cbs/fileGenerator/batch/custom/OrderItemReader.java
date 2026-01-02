package com.silverlakesymmetri.cbs.fileGenerator.batch.custom;

import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import com.silverlakesymmetri.cbs.fileGenerator.entity.Order;
import com.silverlakesymmetri.cbs.fileGenerator.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Iterator;

@Component
public class OrderItemReader implements ItemReader<OrderDto> {

	private static final Logger logger = LoggerFactory.getLogger(OrderItemReader.class);
	private static final int PAGE_SIZE = 1000;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderRowMapper orderRowMapper;

	private Iterator<Order> iterator;
	private int pageNumber = 0;
	private long totalProcessed = 0;
	private boolean initialized = false;

	/**
	 * Read next order with its line items
	 * Lazily loads pages on demand
	 */
	@Override
	public OrderDto read() throws Exception {
		// Load next page if current iterator is exhausted
		if (iterator == null || !iterator.hasNext()) {
			Page<Order> page = loadNextPage();

			if (page == null || !page.hasContent()) {
				logger.info("End of data reached. Total orders processed: {}", totalProcessed);
				return null;
			}

			iterator = page.getContent().iterator();
		}

		Order order = iterator.next();
		totalProcessed++;

		if (totalProcessed % 1000 == 0) {
			logger.info("Processed {} orders", totalProcessed);
		}

		// Map entity to DTO (converts child entities too)
		return orderRowMapper.mapRow(order);
	}

	/**
	 * Load next page with eager fetch of line items
	 */
	private Page<Order> loadNextPage() {
		try {
			Pageable pageable = new PageRequest(pageNumber, PAGE_SIZE);
			Page<Order> page = orderRepository.findAllActiveWithLineItems(pageable);

			logger.debug("Loaded page {} with {} orders", pageNumber, page.getContent().size());
			pageNumber++;

			return page;
		} catch (Exception e) {
			logger.error("Error loading page {}: {}", pageNumber, e.getMessage(), e);
			throw new RuntimeException("Error reading orders from database", e);
		}
	}

	/**
	 * Reset reader state (called at start of job)
	 */
	public void reset() {
		this.iterator = null;
		this.pageNumber = 0;
		this.totalProcessed = 0;
		logger.info("OrderItemReader reset");
	}
}
