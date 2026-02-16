package com.silverlakesymmetri.cbs.fileGenerator.dto;

import javax.xml.bind.annotation.*;
import java.io.Serializable;
import java.math.BigDecimal;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "lineItem", namespace = "http://www.example.com/order")
// IMPORTANT: strict XSD sequence enforcement
@XmlType(namespace = "http://www.example.com/order", propOrder = {
		"lineItemId",
		"productId",
		"productName",
		"quantity",
		"unitPrice",
		"lineAmount",
		"status"
})
public class LineItemDto implements Serializable {
	private String lineItemId;
	// Internal use only - Not in XSD LineItemType
	@XmlTransient
	private String orderId;
	private String productId;
	private String productName;
	private Integer quantity;
	private BigDecimal unitPrice;
	private BigDecimal lineAmount;
	private String status;

	// Constructors
	public LineItemDto() {
	}

	public LineItemDto(String lineItemId, String productId) {
		this.lineItemId = lineItemId;
		this.productId = productId;
	}

	// Getters and Setters
	public String getLineItemId() {
		return lineItemId;
	}

	public void setLineItemId(String lineItemId) {
		this.lineItemId = lineItemId;
	}

	public String getOrderId() {
		return orderId;
	}

	public void setOrderId(String orderId) {
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

	@Override
	public String toString() {
		return "LineItemDto{" +
				"lineItemId='" + lineItemId + '\'' +
				", productId='" + productId + '\'' +
				", quantity=" + quantity +
				'}';
	}
}
