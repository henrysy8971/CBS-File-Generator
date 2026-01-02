package com.silverlakesymmetri.cbs.fileGenerator.batch.custom;

import com.silverlakesymmetri.cbs.fileGenerator.dto.LineItemDto;
import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import org.beanio.BeanWriter;
import org.beanio.StreamFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

@Component
@StepScope
public class OrderItemWriter implements ItemWriter<OrderDto> {
	private static final Logger logger = LoggerFactory.getLogger(OrderItemWriter.class);

	private BeanWriter writer;
	private FileWriter fileWriter;
	private long recordCount = 0;
	private String partFilePath;
	private StepExecution stepExecution;
	private boolean stepSuccessful = false;

	/**
	 * Called before step execution to get job parameters
	 */
	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.stepExecution = stepExecution;
		String outputFilePath = stepExecution.getJobParameters().getString("outputFilePath");
		try {
			init(outputFilePath);
		} catch (Exception e) {
			logger.error("Error initializing OrderItemWriter", e);
			throw new RuntimeException("Error initializing OrderItemWriter", e);
		}
	}

	/**
	 * Initialize writer with output file path
	 */
	public void init(String outputFilePath) throws Exception {
		this.partFilePath = outputFilePath + ".part";
		File outputFile = new File(partFilePath);
		outputFile.getParentFile().mkdirs();

		this.fileWriter = new FileWriter(outputFile, false);

		// Initialize BeanIO writer with order mapping
		try {
			ClassPathResource resource = new ClassPathResource("beanio/order-mapping.xml");
			StreamFactory factory = StreamFactory.newInstance();
			factory.load(resource.getInputStream());
			this.writer = factory.createWriter("interfaceStream", fileWriter);
			logger.info("BeanIO writer initialized for orders with mapping: order-mapping.xml");
		} catch (Exception e) {
			logger.warn("Failed to load BeanIO mapping file for orders, using fallback XML format", e);
		}

		logger.info("OrderItemWriter initialized - temp file: {}", partFilePath);
	}

	/**
	 * Write chunk of orders to file
	 */
	@Override
	public void write(List<? extends OrderDto> items) throws Exception {
		if (items == null || items.isEmpty()) {
			return;
		}

		for (OrderDto orderDto : items) {
			try {
				if (writer != null) {
					writer.write(orderDto);
				} else {
					writeSimpleXml(orderDto);
				}
				recordCount++;
			} catch (Exception e) {
				logger.error("Error writing order: orderId={}", orderDto.getOrderId(), e);
				throw e;
			}
		}

		logger.debug("Wrote {} orders to file", items.size());
	}

	/**
	 * Fallback XML writer (without BeanIO)
	 */
	private void writeSimpleXml(OrderDto orderDto) throws Exception {
		if (recordCount == 0) {
			fileWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
			fileWriter.write("<orderInterface xmlns=\"http://www.example.com/order\">\n");
			fileWriter.write("  <orders>\n");
		}

		fileWriter.write("    <order>\n");
		fileWriter.write(String.format("      <orderId>%s</orderId>\n",
				escapeXml(orderDto.getOrderId())));
		fileWriter.write(String.format("      <orderNumber>%s</orderNumber>\n",
				escapeXml(orderDto.getOrderNumber())));

		if (orderDto.getOrderAmount() != null) {
			fileWriter.write(String.format("      <orderAmount>%s</orderAmount>\n",
					orderDto.getOrderAmount()));
		}

		if (orderDto.getOrderDate() != null) {
			fileWriter.write(String.format("      <orderDate>%s</orderDate>\n",
					orderDto.getOrderDate()));
		}

		if (orderDto.getCustomerName() != null) {
			fileWriter.write(String.format("      <customerName>%s</customerName>\n",
					escapeXml(orderDto.getCustomerName())));
		}

		// Write line items
		if (!orderDto.getLineItems().isEmpty()) {
			fileWriter.write("      <lineItems>\n");
			for (int i = 0; i < orderDto.getLineItems().size(); i++) {
				LineItemDto lineItem = orderDto.getLineItems().get(i);
				fileWriter.write("        <lineItem>\n");
				fileWriter.write(String.format("          <lineItemId>%s</lineItemId>\n",
						escapeXml(lineItem.getLineItemId())));
				fileWriter.write(String.format("          <productId>%s</productId>\n",
						escapeXml(lineItem.getProductId())));
				if (lineItem.getProductName() != null) {
					fileWriter.write(String.format("          <productName>%s</productName>\n",
							escapeXml(lineItem.getProductName())));
				}
				if (lineItem.getQuantity() != null) {
					fileWriter.write(String.format("          <quantity>%d</quantity>\n",
							lineItem.getQuantity()));
				}
				if (lineItem.getUnitPrice() != null) {
					fileWriter.write(String.format("          <unitPrice>%s</unitPrice>\n",
							lineItem.getUnitPrice()));
				}
				if (lineItem.getLineAmount() != null) {
					fileWriter.write(String.format("          <lineAmount>%s</lineAmount>\n",
							lineItem.getLineAmount()));
				}
				fileWriter.write("        </lineItem>\n");
			}
			fileWriter.write("      </lineItems>\n");
		}

		fileWriter.write("    </order>\n");
	}

	/**
	 * Close writer and finalize file
	 */
	public void close() throws Exception {
		try {
			if (recordCount > 0 && writer == null) {
				fileWriter.write("  </orders>\n");
				fileWriter.write(String.format("  <totalRecords>%d</totalRecords>\n", recordCount));
				fileWriter.write("</orderInterface>\n");
			}

			if (writer != null) {
				writer.close();
			}

			if (fileWriter != null) {
				fileWriter.close();
			}

			// Store part file path and record count in execution context for job listener
			if (stepExecution != null) {
				stepExecution.getJobExecution().getExecutionContext().put("partFilePath", partFilePath);
				stepExecution.getJobExecution().getExecutionContext().put("recordCount", recordCount);
			}

			logger.info("OrderItemWriter closed. Total records written: {}", recordCount);
		} catch (Exception e) {
			logger.error("Error closing OrderItemWriter", e);
			throw e;
		}
	}

	public long getRecordCount() {
		return recordCount;
	}

	public String getPartFilePath() {
		return partFilePath;
	}

	private String escapeXml(String text) {
		if (text == null) {
			return "";
		}
		return text.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&apos;");
	}

	public void setStepSuccessful(boolean success) {
		this.stepSuccessful = success;
	}
}
