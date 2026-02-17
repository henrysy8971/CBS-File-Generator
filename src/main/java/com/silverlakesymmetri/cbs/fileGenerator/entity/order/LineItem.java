package com.silverlakesymmetri.cbs.fileGenerator.entity.order;

import javax.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;

@Entity
@Table(name = "LINE_ITEMS")
public class LineItem implements Serializable {
	@Id
	@Column(name = "LINE_ITEM_ID")
	private String lineItemId;

	@Column(name = "ORDER_ID", nullable = false)
	private Long orderId;

	@Column(name = "PRODUCT_ID")
	private String productId;

	@Column(name = "PRODUCT_NAME")
	private String productName;

	@Column(name = "QUANTITY")
	private Integer quantity;

	@Column(name = "UNIT_PRICE")
	private BigDecimal unitPrice;

	@Column(name = "LINE_AMOUNT")
	private BigDecimal lineAmount;

	@Column(name = "STATUS", length = 20)
	private String status;

	// Parent relationship
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "ORDER_ID", insertable = false, updatable = false)
	private Order order;

	// Constructors
	public LineItem() {
	}

	public LineItem(String lineItemId, Long orderId, String productId) {
		this.lineItemId = lineItemId;
		this.orderId = orderId;
		this.productId = productId;
	}

	// Getters and Setters
	public String getLineItemId() {
		return lineItemId;
	}

	public void setLineItemId(String lineItemId) {
		this.lineItemId = lineItemId;
	}

	public Long getOrderId() {
		return orderId;
	}

	public void setOrderId(Long orderId) {
		this.orderId = orderId;
	}

	public String getProductId() {
		return productId;
	}

	public void setProductId(String productId) {
		this.productId = productId;
	}

	public String getProductName() {
		return productName;
	}

	public void setProductName(String productName) {
		this.productName = productName;
	}

	public Integer getQuantity() {
		return quantity;
	}

	public void setQuantity(Integer quantity) {
		this.quantity = quantity;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}

	public void setUnitPrice(BigDecimal unitPrice) {
		this.unitPrice = unitPrice;
	}

	public BigDecimal getLineAmount() {
		return lineAmount;
	}

	public void setLineAmount(BigDecimal lineAmount) {
		this.lineAmount = lineAmount;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Order getOrder() {
		return order;
	}

	public void setOrder(Order order) {
		this.order = order;
	}
}
