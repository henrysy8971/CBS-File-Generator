package com.silverlakesymmetri.cbs.fileGenerator.entity.order;

import javax.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ORDERS")
public class Order implements Serializable {
	@Id
	@Column(name = "ORDER_ID")
	private Long orderId;

	@Column(name = "ORDER_NUMBER", nullable = false)
	private String orderNumber;

	@Column(name = "ORDER_AMOUNT")
	private BigDecimal orderAmount;

	@Column(name = "ORDER_DATE")
	private java.sql.Timestamp orderDate;

	@Column(name = "CUSTOMER_ID")
	private String customerId;

	@Column(name = "CUSTOMER_NAME")
	private String customerName;

	@Column(name = "STATUS", length = 20)
	private String status;

	// Child relationship - eagerly loaded
	@OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
	private List<LineItem> lineItems;

	// Constructors
	public Order() {
		this.lineItems = new ArrayList<>();
	}

	public Order(Long orderId, String orderNumber) {
		this();
		this.orderId = orderId;
		this.orderNumber = orderNumber;
	}

	// Getters and Setters
	public Long getOrderId() {
		return orderId;
	}

	public void setOrderId(Long orderId) {
		this.orderId = orderId;
	}

	public String getOrderNumber() {
		return orderNumber;
	}

	public void setOrderNumber(String orderNumber) {
		this.orderNumber = orderNumber;
	}

	public BigDecimal getOrderAmount() {
		return orderAmount;
	}

	public void setOrderAmount(BigDecimal orderAmount) {
		this.orderAmount = orderAmount;
	}

	public java.sql.Timestamp getOrderDate() {
		return orderDate;
	}

	public void setOrderDate(java.sql.Timestamp orderDate) {
		this.orderDate = orderDate;
	}

	public String getCustomerId() {
		return customerId;
	}

	public void setCustomerId(String customerId) {
		this.customerId = customerId;
	}

	public String getCustomerName() {
		return customerName;
	}

	public void setCustomerName(String customerName) {
		this.customerName = customerName;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public List<LineItem> getLineItems() {
		return lineItems;
	}

	public void setLineItems(List<LineItem> lineItems) {
		this.lineItems = lineItems;
	}

	public void addLineItem(LineItem lineItem) {
		this.lineItems.add(lineItem);
		lineItem.setOrder(this);
	}
}
