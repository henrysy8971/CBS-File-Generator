package com.silverlakesymmetri.cbs.fileGenerator.repository;

import com.silverlakesymmetri.cbs.fileGenerator.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

	/**
	 * Find all active orders with eagerly loaded line items
	 * Uses JOIN FETCH to avoid N+1 query problem
	 */
	@Query("SELECT DISTINCT o FROM Order o " +
			"LEFT JOIN FETCH o.lineItems li " +
			"WHERE o.status = 'ACTIVE' " +
			"ORDER BY o.orderId")
	Page<Order> findAllActiveWithLineItems(Pageable pageable);

	/**
	 * Find orders by status with line items
	 */
	@Query("SELECT DISTINCT o FROM Order o " +
			"LEFT JOIN FETCH o.lineItems li " +
			"WHERE o.status = :status " +
			"ORDER BY o.orderId")
	List<Order> findByStatusWithLineItems(String status);

	/**
	 * Find order by ID with line items
	 */
	@Query("SELECT DISTINCT o FROM Order o " +
			"LEFT JOIN FETCH o.lineItems li " +
			"WHERE o.orderId = :orderId")
	Optional<Order> findByIdWithLineItems(String orderId);

	/**
	 * Count active orders
	 */
	long countByStatus(String status);
}
