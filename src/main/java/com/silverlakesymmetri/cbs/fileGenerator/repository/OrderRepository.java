package com.silverlakesymmetri.cbs.fileGenerator.repository;

import com.silverlakesymmetri.cbs.fileGenerator.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
	/**
	 * First page of ACTIVE orders (no fetch join, paging-safe)
	 */
	@Query("SELECT o FROM Order o " +
			"WHERE o.status = 'ACTIVE' " +
			"ORDER BY o.orderId ASC")
	Page<Order> findFirstActive(Pageable pageable);

	/**
	 * Restart-safe keyset pagination
	 */
	@Query("SELECT o FROM Order o " +
			"WHERE o.status = 'ACTIVE' " +
			"AND o.orderId > :lastId " +
			"ORDER BY o.orderId ASC")
	Page<Order> findActiveAfterId(long lastId, Pageable pageable);

	@Query("SELECT DISTINCT o FROM Order o " +
			"LEFT JOIN FETCH o.lineItems " +
			"WHERE o.orderId IN :orderIds")
	List<Order> findWithLineItemsByOrderIdIn(List<Long> orderIds);
}
