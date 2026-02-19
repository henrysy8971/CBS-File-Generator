package com.silverlakesymmetri.cbs.fileGenerator.repository;

import com.silverlakesymmetri.cbs.fileGenerator.entity.order.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.persistence.Tuple;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
	// Fetch only IDs as Tuples for high-performance paging
	@Query("SELECT o.orderId AS id" +
			" FROM Order o" +
			" WHERE o.status = :status" +
			" ORDER BY o.orderId ASC"
	)
	Slice<Tuple> findActiveIds(
			@Param("status") String status,
			Pageable pageable
	);

	@Query("SELECT o.orderId as id" +
			" FROM Order o" +
			" WHERE o.status = :status" +
			" AND o.orderId > :lastId" +
			" ORDER BY o.orderId ASC"
	)
	Slice<Tuple> findActiveIdsAfter(
			@Param("status") String status,
			@Param("lastId") Long lastId,
			Pageable pageable
	);

	@Query("SELECT DISTINCT o" +
			" FROM Order o" +
			" LEFT JOIN FETCH o.lineItems" +
			" WHERE o.orderId IN :orderIds" +
			" ORDER BY o.orderId ASC")
	List<Order> findWithLineItemsByOrderIdIn(
			@Param("orderIds") List<Long> orderIds
	);
}
