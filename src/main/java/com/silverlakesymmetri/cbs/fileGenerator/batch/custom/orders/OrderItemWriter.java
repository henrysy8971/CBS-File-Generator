package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.xml.StaxEventItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.xml.stream.XMLEventFactory;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
@StepScope
public class OrderItemWriter extends StaxEventItemWriter<OrderDto> implements StepExecutionListener {
	private static final Logger logger = LoggerFactory.getLogger(OrderItemWriter.class);
	private static final String NS_URI = "http://www.example.com/order";
	private static final String NS_PREFIX = "tns";
	private final AtomicLong totalRecordCount = new AtomicLong(0);
	private final String partFilePath;

	public OrderItemWriter(@Value("#{jobParameters['outputFilePath']}") String partFilePath) {
		this.partFilePath = Objects.requireNonNull(partFilePath, "outputFilePath is required");
		File file = new File(partFilePath);
		if (file.getParentFile() != null && !file.getParentFile().exists()) {
			file.getParentFile().mkdirs();
		}
		this.setResource(new FileSystemResource(file));
	}

	@PostConstruct
	public void init() {
		// 1. Configure JAXB Marshaller
		Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
		marshaller.setClassesToBeBound(OrderDto.class);

		Map<String, Object> props = new HashMap<>();
		props.put(javax.xml.bind.Marshaller.JAXB_FORMATTED_OUTPUT, true);
		marshaller.setMarshallerProperties(props);
		this.setMarshaller(marshaller);

		// 2. Configure Root Tag (<tns:orders>)
		this.setRootTagName(NS_PREFIX + ":orders");
		this.setRootElementAttributes(Collections.singletonMap("xmlns:" + NS_PREFIX, NS_URI));

		// 3. Configure Header (Writes <orderInterface>)
		this.setHeaderCallback(writer -> {
			XMLEventFactory factory = XMLEventFactory.newInstance();
			try {
				writer.add(factory.createStartElement(NS_PREFIX, NS_URI, "orderInterface"));
				writer.add(factory.createNamespace(NS_PREFIX, NS_URI));
			} catch (Exception e) {
				logger.error("Failed to write XML header for file: {}", this.partFilePath, e);
				throw new IOException("Failed to write XML header", e);
			}
		});

		// 4. Configure Footer (Writes count + </orderInterface>)
		this.setFooterCallback(writer -> {
			XMLEventFactory factory = XMLEventFactory.newInstance();
			try {
				// Write <tns:totalRecords>COUNT</tns:totalRecords>
				writer.add(factory.createStartElement(NS_PREFIX, NS_URI, "totalRecords"));
				writer.add(factory.createCharacters(String.valueOf(totalRecordCount.get())));
				writer.add(factory.createEndElement(NS_PREFIX, NS_URI, "totalRecords"));

				// </tns:orderInterface>
				writer.add(factory.createEndElement(NS_PREFIX, NS_URI, "orderInterface"));
			} catch (Exception e) {
				logger.error("Failed to write XML footer for file: {}", this.partFilePath, e);
				throw new IOException("Failed to write XML footer", e);
			}
		});

		// 5. Enable Overwrite (Required for .part file logic usually, though restart handles append)
		this.setOverwriteOutput(false); // standard batch behavior is usually false for restarts
	}

	/**
	 * Override write to track record count locally for the FooterCallback.
	 */
	@Override
	public void write(List<? extends OrderDto> items) throws Exception {
		logger.debug("Writing {} orders to file: {}", items.size(), this.partFilePath);
		totalRecordCount.getAndAdd(items.size());
		super.write(items);
	}

	// --- StepExecutionListener Logic ---
	@Override
	public void beforeStep(StepExecution stepExecution) {
		// On restart, sync the local count with what's already in the DB
		long restoredCount = stepExecution.getJobExecution().getExecutionContext().getLong("totalRecordCount", 0L);
		totalRecordCount.set(restoredCount);
		logger.info("Restoring writer state. Current count: {}", totalRecordCount.get());
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();

		// Pass the file path to the JobContext for the JobExecutionListener
		if (this.partFilePath != null) {
			jobContext.putString("partFilePath", this.partFilePath);
			logger.debug("Step {} handed off partFilePath to JobContext: {}",
					stepExecution.getStepName(), this.partFilePath);
		}

		// Store total count
		jobContext.putLong("totalRecordCount", totalRecordCount.get());

		if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
			logger.warn("Step {} completed with status: {}", stepExecution.getStepName(), stepExecution.getStatus());
		}

		return stepExecution.getStatus() == BatchStatus.COMPLETED ? ExitStatus.COMPLETED : ExitStatus.FAILED;
	}
}
