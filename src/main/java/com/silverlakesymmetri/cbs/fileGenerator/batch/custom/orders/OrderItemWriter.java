package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.dto.OrderDto;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@StepScope
public class OrderItemWriter extends StaxEventItemWriter<OrderDto> implements StepExecutionListener {
	private static final Logger logger = LoggerFactory.getLogger(OrderItemWriter.class);
	private static final String NS_URI = "http://www.example.com/order";
	private static final String NS_PREFIX = "tns";

	private final FileGenerationService fileGenerationService;
	private String partFilePath;
	private long totalRecordCount = 0;

	public OrderItemWriter(FileGenerationService fileGenerationService) {
		this.fileGenerationService = fileGenerationService;
	}

	@Value("#{jobParameters['outputFilePath']}")
	public void setPartFilePath(String partFilePath) {
		this.partFilePath = partFilePath;

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
				throw new IOException("Failed to write XML header", e);
			}
		});

		// 4. Configure Footer (Writes count + </orderInterface>)
		this.setFooterCallback(writer -> {
			XMLEventFactory factory = XMLEventFactory.newInstance();
			try {
				// Write <tns:totalRecords>COUNT</tns:totalRecords>
				writer.add(factory.createStartElement(NS_PREFIX, NS_URI, "totalRecords"));
				writer.add(factory.createCharacters(String.valueOf(totalRecordCount)));
				writer.add(factory.createEndElement(NS_PREFIX, NS_URI, "totalRecords"));

				// </tns:orderInterface>
				writer.add(factory.createEndElement(NS_PREFIX, NS_URI, "orderInterface"));
			} catch (Exception e) {
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
		totalRecordCount += items.size();
		super.write(items);
	}

	// --- StepExecutionListener Logic ---
	@Override
	public void beforeStep(StepExecution stepExecution) {
		// On restart, sync the local count with what's already in the DB
		long existingCount = stepExecution.getWriteCount();
		if (existingCount > 0) {
			this.totalRecordCount = existingCount;
			logger.info("Restoring writer state. Current count: {}", totalRecordCount);
		}
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		String jobId = stepExecution.getJobParameters().getString("jobId");
		ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();

		// Pass the file path to the JobContext for the DynamicJobExecutionListener
		if (this.partFilePath != null) {
			jobContext.putString("partFilePath", this.partFilePath);
			logger.debug("Handed off partFilePath to JobContext: {}", this.partFilePath);
		}

		// Store total count
		jobContext.putLong("totalRecordCount", totalRecordCount);

		// Sync Metrics
		long processed = stepExecution.getWriteCount();
		long skipped = stepExecution.getProcessSkipCount();
		long invalid = stepExecution.getWriteSkipCount() + stepExecution.getFilterCount();

		if (jobId != null) {
			fileGenerationService.updateFileMetrics(jobId, processed, skipped, invalid);
			logger.info("Order metrics synced: jobId={}, processed={}, skipped={}, invalid={}",
					jobId, processed, skipped, invalid);
		}

		return stepExecution.getExitStatus();
	}

	// Setter used by Listener (Kept for backward compatibility if Listener calls it,
	// though StaxEventItemWriter handles transaction safety automatically now)
	public void setStepSuccessful(boolean stepSuccessful) {
		// No-op: StaxEventItemWriter automatically handles closing tags
		// only if the transaction commits successfully.
	}

	public String getPartFilePath() {
		return partFilePath;
	}
}
