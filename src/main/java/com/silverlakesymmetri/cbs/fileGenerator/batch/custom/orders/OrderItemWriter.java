package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.dto.LineItemDto;
import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@StepScope
public class OrderItemWriter implements ItemStreamWriter<OrderDto>, StepExecutionListener {
	private static final Logger logger = LoggerFactory.getLogger(OrderItemWriter.class);
	private static final String RESTART_COUNT_KEY = "order.writer.recordCount";
	private static final String NS_URI = "http://www.example.com/order";
	private static final String NS_PREFIX = "tns";

	private XMLStreamWriter xmlStreamWriter;
	private Writer underlyingWriter;
	private boolean stepSuccessful = false;
	private long recordCount = 0;
	private String partFilePath;

	@Value("#{jobParameters['outputFilePath']}")
	public void setPartFilePath(String partFilePath) {
		this.partFilePath = partFilePath;
	}

	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		try {
			File file = new File(partFilePath);
			if (file.getParentFile() != null && !file.getParentFile().exists()) {
				file.getParentFile().mkdirs();
			}

			if (this.recordCount == 0 && executionContext.containsKey(RESTART_COUNT_KEY)) {
				this.recordCount = executionContext.getLong(RESTART_COUNT_KEY);
			}

			boolean append = this.recordCount > 0;

			this.underlyingWriter = new BufferedWriter(new OutputStreamWriter(
					new FileOutputStream(file, append), StandardCharsets.UTF_8));
			xmlStreamWriter = XMLOutputFactory.newInstance().createXMLStreamWriter(underlyingWriter);
			xmlStreamWriter.setPrefix(NS_PREFIX, NS_URI);

			if (!append) {
				xmlStreamWriter.writeStartDocument("UTF-8", "1.0");
				xmlStreamWriter.writeStartElement(NS_PREFIX, "orderInterface", NS_URI);
				xmlStreamWriter.writeNamespace(NS_PREFIX, NS_URI);
				xmlStreamWriter.writeStartElement(NS_PREFIX, "orders", NS_URI);
			}

			logger.info("XML writer initialized for file {}. Restart: {}", partFilePath, append);
		} catch (Exception e) {
			closeResources();
			throw new ItemStreamException("Failed to initialize XML writer", e);
		}
	}

	@Override
	public void write(List<? extends OrderDto> items) throws ItemStreamException {
		if (xmlStreamWriter == null) {
			throw new ItemStreamException("XML writer is not initialized");
		}

		try {
			for (OrderDto order : items) {
				xmlStreamWriter.writeStartElement(NS_PREFIX, "order", NS_URI);

				writeSimpleElement("orderId", order.getOrderId());
				writeSimpleElement("orderNumber", order.getOrderNumber());
				writeSimpleElement("orderAmount", order.getOrderAmount());
				writeSimpleElement("orderDate", order.getOrderDate());
				writeSimpleElement("customerId", order.getCustomerId());
				writeSimpleElement("customerName", order.getCustomerName());
				writeSimpleElement("status", order.getStatus());

				if (order.getLineItems() != null && !order.getLineItems().isEmpty()) {
					xmlStreamWriter.writeStartElement(NS_PREFIX, "lineItems", NS_URI);
					for (LineItemDto item : order.getLineItems()) {
						xmlStreamWriter.writeStartElement(NS_PREFIX, "lineItem", NS_URI);
						writeSimpleElement("lineItemId", item.getLineItemId());
						writeSimpleElement("productId", item.getProductId());
						writeSimpleElement("productName", item.getProductName());
						writeSimpleElement("quantity", item.getQuantity());
						writeSimpleElement("unitPrice", item.getUnitPrice());
						writeSimpleElement("lineAmount", item.getLineAmount());
						writeSimpleElement("status", item.getStatus());
						xmlStreamWriter.writeEndElement(); // lineItem
					}
					xmlStreamWriter.writeEndElement(); // lineItems
				}

				xmlStreamWriter.writeEndElement(); // order
				recordCount++;
			}
			xmlStreamWriter.flush(); // flush per chunk
		} catch (Exception e) {
			throw new ItemStreamException("Failed to write XML records", e);
		}
	}

	@Override
	public void beforeStep(StepExecution stepExecution) {
		// Sync record count with the framework's official write count
		long lastWriteCount = stepExecution.getWriteCount();
		if (lastWriteCount > 0) {
			this.recordCount = lastWriteCount;
			logger.info("Restart detected. Resuming Order XML count from: {}", recordCount);
		}
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		this.stepSuccessful = !stepExecution.getStatus().isUnsuccessful();
		return null;
	}

	// Helper to write an element safely
	private void writeSimpleElement(String name, Object value) throws Exception {
		xmlStreamWriter.writeStartElement(NS_PREFIX, name, NS_URI);
		if (value != null) {
			if (value instanceof BigDecimal) {
				xmlStreamWriter.writeCharacters(((BigDecimal) value).toPlainString());
			} else {
				xmlStreamWriter.writeCharacters(value.toString());
			}
		}
		xmlStreamWriter.writeEndElement();
	}

	@Override
	public void update(ExecutionContext executionContext) throws ItemStreamException {
		executionContext.putLong(RESTART_COUNT_KEY, recordCount);
	}

	@Override
	public void close() throws ItemStreamException {
		if (xmlStreamWriter != null) {
			try {
				if (stepSuccessful) {
					// Close XML properly only if step finished successfully
					xmlStreamWriter.writeEndElement(); // orders
					writeSimpleElement("totalRecords", recordCount);
					xmlStreamWriter.writeEndElement(); // orderInterface
					xmlStreamWriter.writeEndDocument();
					xmlStreamWriter.flush();
				}
			} catch (Exception e) {
				throw new ItemStreamException("Failed to close XML writer properly", e);
			} finally {
				closeResources();
			}
		}
	}

	private void closeResources() {
		try {
			if (xmlStreamWriter != null) xmlStreamWriter.close();
		} catch (Exception e) {
			logger.warn("Error closing XMLStreamWriter: {}", e.getMessage());
		}
		try {
			if (underlyingWriter != null) underlyingWriter.close();
		} catch (Exception e) {
			logger.warn("Error closing underlying file writer: {}", e.getMessage());
		}
		xmlStreamWriter = null;
		underlyingWriter = null;
	}

	// Setter used by the Listener to mark step success
	public void setStepSuccessful(boolean stepSuccessful) {
		this.stepSuccessful = stepSuccessful;
	}

	public long getRecordCount() {
		return recordCount;
	}

	public String getPartFilePath() {
		return partFilePath;
	}
}
