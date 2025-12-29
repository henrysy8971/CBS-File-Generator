package com.silverlakesymmetri.cbs.fileGenerator.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class OrderDto implements Serializable {

	private String orderId;
	private String orderNumber;
	private BigDecimal orderAmount;
	private String orderDate;
	private String customerId;
	private String customerName;
	private String status;

	// Child list
	private List<LineItemDto> lineItems;

	// Constructors
	public OrderDto() {
		this.lineItems = new ArrayList<>();
	}

	public OrderDto(String orderId, String orderNumber) {
		this();
		this.orderId = orderId;
		this.orderNumber = orderNumber;
	}

	// Getters and Setters
	public String getOrderId() {
		return orderId;
	}

	public void setOrderId(String orderId) {
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

	public String getOrderDate() {
		return orderDate;
	}

	public void setOrderDate(String orderDate) {
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

	public List<LineItemDto> getLineItems() {
		return lineItems;
	}

	public void setLineItems(List<LineItemDto> lineItems) {
		this.lineItems = lineItems;
	}

	public void addLineItem(LineItemDto lineItemDto) {
		this.lineItems.add(lineItemDto);
	}

	@Override
	public String toString() {
		return "OrderDto{" +
				"orderId='" + orderId + '\'' +
				", orderNumber='" + orderNumber + '\'' +
				", orderAmount=" + orderAmount +
				", lineItems=" + lineItems.size() +
				'}';
	}
}
