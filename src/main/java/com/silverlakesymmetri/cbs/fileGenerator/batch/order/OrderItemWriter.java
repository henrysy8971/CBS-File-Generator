package com.silverlakesymmetri.cbs.fileGenerator.batch.order;

import com.silverlakesymmetri.cbs.fileGenerator.batch.AbstractBaseOutputWriter;
import com.silverlakesymmetri.cbs.fileGenerator.dto.order.OrderDto;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;

import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Result;
import javax.xml.transform.stax.StAXResult;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.FILE_GEN_PART_FILE_PATH;
import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.FILE_GEN_TOTAL_RECORD_COUNT;

@Component
@StepScope
public class OrderItemWriter extends AbstractBaseOutputWriter<OrderDto> {

	// XML Constants
	private static final String NS_URI = "http://www.example.com/order";
	private static final String ITEM_TAG = "order";
	private static final String NS_PREFIX = "tns";
	private static final String ROOT_TAG = "orderInterface";
	private static final String WRAPPER_TAG = "orders";

	private Jaxb2Marshaller marshaller;
	private XMLStreamWriter xmlStreamWriter;

	@Autowired
	public OrderItemWriter(@Value("#{jobParameters['outputFilePath']}") String outputFilePath) throws Exception {
		// Initialize the base class with path and interface type
		super.init(outputFilePath, "ORDER_INTERFACE");
	}

	@Override
	protected String getByteOffsetKey() {
		return "order.writer.byteOffset";
	}

	@Override
	protected String getRecordCountKey() {
		return "order.writer.recordCount";
	}

	@Override
	protected void onInit() {
		// Initialize JAXB Marshaller
		marshaller = new Jaxb2Marshaller();
		marshaller.setClassesToBeBound(OrderDto.class);
		marshaller.setMarshallerProperties(Collections.singletonMap(
				javax.xml.bind.Marshaller.JAXB_FORMATTED_OUTPUT, false
		));
	}

	@Override
	protected void openStream(OutputStream os, boolean isRestart) throws Exception {
		xmlStreamWriter = XMLOutputFactory.newInstance()
				.createXMLStreamWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));

		if (!isRestart) {
			writeHeader();
		}
	}

	@Override
	//public abstract void write(List<? extends T> items) throws Exception;
	public void write(List<? extends OrderDto> items) throws Exception {
		if (xmlStreamWriter == null) throw new IllegalStateException("Writer not opened");

		for (OrderDto order : items) {
			if (order == null) continue;

			// Wrap DTO in JAXBElement to enforce Namespace/Prefix consistency
			JAXBElement<OrderDto> element = new JAXBElement<>(
					new QName(NS_URI, ITEM_TAG, NS_PREFIX),
					OrderDto.class,
					order
			);

			Result result = new StAXResult(xmlStreamWriter);
			try {
				marshaller.marshal(element, result);
				recordCount++;
			} catch (Exception e) {
				logger.error("Failed to marshal orderId={}", order.getOrderId(), e);
				throw e;
			}
		}
	}

	@Override
	protected void flushInternal() throws Exception {
		if (xmlStreamWriter != null) {
			xmlStreamWriter.flush();
		}
	}

	@Override
	protected void writeHeader() throws Exception {
		xmlStreamWriter.writeStartDocument("UTF-8", "1.0");
		xmlStreamWriter.writeStartElement(NS_PREFIX, ROOT_TAG, NS_URI);
		xmlStreamWriter.writeNamespace(NS_PREFIX, NS_URI);
		xmlStreamWriter.writeStartElement(NS_PREFIX, WRAPPER_TAG, NS_URI);
		xmlStreamWriter.flush();
	}

	@Override
	protected void writeFooter() throws Exception {
		// Close <orders>
		xmlStreamWriter.writeEndElement();

		// Write Total Records
		xmlStreamWriter.writeStartElement(NS_PREFIX, "totalRecords", NS_URI);
		xmlStreamWriter.writeCharacters(String.valueOf(recordCount));
		xmlStreamWriter.writeEndElement();

		// Close <orderInterface>
		xmlStreamWriter.writeEndElement();
		xmlStreamWriter.writeEndDocument();
		xmlStreamWriter.flush();
	}

	@Override
	public void close() {
		try {
			if (xmlStreamWriter != null) {
				if (stepSuccessful) {
					writeFooter();
				}
				xmlStreamWriter.close();
			}
		} catch (Exception e) {
			logger.error("Error closing Order XML writer", e);
		} finally {
			super.closeQuietly();
		}
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		// Call parent logic first to set stepSuccessful
		ExitStatus exitStatus = super.afterStep(stepExecution);

		// Populate Job Context with metadata for the JobListener to rename/move the file
		ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
		jobContext.putString(FILE_GEN_PART_FILE_PATH, partFilePath);
		jobContext.putLong(FILE_GEN_TOTAL_RECORD_COUNT, recordCount);

		return exitStatus;
	}
}