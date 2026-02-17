package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.batch.ByteTrackingOutputStream;
import com.silverlakesymmetri.cbs.fileGenerator.dto.order.OrderDto;
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
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;

import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Result;
import javax.xml.transform.stax.StAXResult;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Component
@StepScope
public class OrderItemWriter implements ItemStreamWriter<OrderDto>, StepExecutionListener {

	private static final Logger logger = LoggerFactory.getLogger(OrderItemWriter.class);

	// XML Constants
	private static final String NS_URI = "http://www.example.com/order";
	private static final String NS_PREFIX = "tns";
	private static final String ROOT_TAG = "orderInterface";
	private static final String WRAPPER_TAG = "orders";
	private static final String ITEM_TAG = "order";

	// Context Keys
	private static final String BYTE_OFFSET_KEY = "order.writer.byte.offset";
	private static final String RECORD_COUNT_KEY = "order.writer.record.count";

	private final String partFilePath;
	private final Jaxb2Marshaller marshaller;

	// Streams & State
	private FileOutputStream fos;
	private BufferedOutputStream bufferedOutputStream;
	private ByteTrackingOutputStream byteTrackingStream;
	private XMLStreamWriter xmlStreamWriter;

	private long recordCount = 0;
	private final Object lock = new Object();

	public OrderItemWriter(@Value("#{jobParameters['outputFilePath']}") String partFilePath) {
		this.partFilePath = Objects.requireNonNull(partFilePath, "outputFilePath is required");

		// Initialize JAXB
		this.marshaller = new Jaxb2Marshaller();
		this.marshaller.setClassesToBeBound(OrderDto.class);
		// Ensure formatted output for readability
		this.marshaller.setMarshallerProperties(Collections.singletonMap(
				javax.xml.bind.Marshaller.JAXB_FORMATTED_OUTPUT, true
		));
	}

	// -------------------------------------------------------------------------
	// Lifecycle: Open
	// -------------------------------------------------------------------------
	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		try {
			File file = new File(partFilePath);
			ensureParentDirectory(file);

			long offset = 0;
			boolean isRestart = false;

			if (executionContext.containsKey(BYTE_OFFSET_KEY)) {
				offset = executionContext.getLong(BYTE_OFFSET_KEY);
				this.recordCount = executionContext.getLong(RECORD_COUNT_KEY);
				isRestart = true;
			}

			// 1. Open Stream (Append Mode)
			this.fos = new FileOutputStream(file, true);
			FileChannel channel = this.fos.getChannel();

			// 2. Truncate Logic (Safety for Restarts)
			if (isRestart) {
				// If restarting, we might have written a "Failure Footer" or partial bytes.
				// We truncate back to the last committed checkpoint.
				if (offset < channel.size()) {
					channel.truncate(offset);
					logger.info("Restart detected. Truncating file to offset {}, resuming from record {}", offset, recordCount);
				}
			} else {
				// Fresh run: wipe file
				channel.truncate(0);
			}

			// 3. Chain Streams: FOS -> ByteTracker -> Buffer -> XMLWriter
			this.byteTrackingStream = new ByteTrackingOutputStream(this.fos, offset);
			this.bufferedOutputStream = new BufferedOutputStream(byteTrackingStream);

			this.xmlStreamWriter = XMLOutputFactory.newInstance()
					.createXMLStreamWriter(new OutputStreamWriter(bufferedOutputStream, StandardCharsets.UTF_8));

			// 4. Header (Only on fresh run)
			if (!isRestart) {
				writeHeader();
			} else {
				logger.info("Resuming XML writing. Header preserved.");
			}

		} catch (Exception e) {
			closeQuietly();
			throw new ItemStreamException("Failed to open OrderItemWriter", e);
		}
	}

	// -------------------------------------------------------------------------
	// Lifecycle: Write
	// -------------------------------------------------------------------------
	@Override
	public void write(List<? extends OrderDto> items) throws Exception {
		if (items == null || items.isEmpty()) return;

		synchronized (lock) {
			for (OrderDto order : items) {
				// Wrap DTO in JAXBElement to enforce Namespace/Prefix consistency
				// This protects against DTOs missing @XmlRootElement or having wrong namespaces
				JAXBElement<OrderDto> element = new JAXBElement<>(
						new QName(NS_URI, ITEM_TAG, NS_PREFIX),
						OrderDto.class,
						order
				);

				Result result = new StAXResult(xmlStreamWriter);
				marshaller.marshal(element, result);

				recordCount++;
			}
		}
	}

	// -------------------------------------------------------------------------
	// Lifecycle: Update
	// -------------------------------------------------------------------------
	@Override
	public void update(ExecutionContext executionContext) {
		try {
			// FLUSH ORDER IS CRITICAL
			if (xmlStreamWriter != null) {
				xmlStreamWriter.flush(); // Pushes XML to Buffer
			}
			if (bufferedOutputStream != null) {
				bufferedOutputStream.flush(); // Pushes Buffer to ByteTracker -> Disk
			}

			// Now the ByteTracker has the exact count of bytes physically on disk
			if (byteTrackingStream != null) {
				long currentOffset = byteTrackingStream.getBytesWritten();
				executionContext.putLong(BYTE_OFFSET_KEY, currentOffset);
				executionContext.putLong(RECORD_COUNT_KEY, recordCount);
			}
		} catch (Exception e) {
			throw new ItemStreamException("Failed to update ExecutionContext", e);
		}
	}

	// -------------------------------------------------------------------------
	// Lifecycle: Close
	// -------------------------------------------------------------------------
	@Override
	public void close() {
		synchronized (lock) {
			try {
				if (xmlStreamWriter != null) {
					// We ALWAYS write the footer, even on failure.
					// This ensures the XML file is valid for inspection.
					// On restart, the open() method will truncate this footer because
					// its bytes were never saved to ExecutionContext.
					writeFooter();

					xmlStreamWriter.close();
				}
			} catch (Exception e) {
				logger.error("Error closing XML writer", e);
			} finally {
				// Ensure underlying streams are definitely closed
				closeQuietly();
				xmlStreamWriter = null;
				byteTrackingStream = null;
				fos = null;
			}
		}
	}

	// -------------------------------------------------------------------------
	// XML Helpers
	// -------------------------------------------------------------------------
	private void writeHeader() throws XMLStreamException, IOException {
		xmlStreamWriter.writeStartDocument("UTF-8", "1.0");
		xmlStreamWriter.writeStartElement(NS_PREFIX, ROOT_TAG, NS_URI);
		xmlStreamWriter.writeNamespace(NS_PREFIX, NS_URI);

		// Start the wrapper list tag
		xmlStreamWriter.writeStartElement(NS_PREFIX, WRAPPER_TAG, NS_URI);

		// Flush immediately to ensure file structure is created
		xmlStreamWriter.flush();
		bufferedOutputStream.flush();
	}

	private void writeFooter() throws XMLStreamException, IOException {
		// Close <orders>
		xmlStreamWriter.writeEndElement();

		// Write Total Records
		xmlStreamWriter.writeStartElement(NS_PREFIX, "totalRecords", NS_URI);
		xmlStreamWriter.writeCharacters(String.valueOf(recordCount));
		xmlStreamWriter.writeEndElement();

		// Close <orderInterface>
		xmlStreamWriter.writeEndElement();
		xmlStreamWriter.writeEndDocument();

		// Aggressive flush
		xmlStreamWriter.flush();
		if (bufferedOutputStream != null) bufferedOutputStream.flush();
	}

	private void ensureParentDirectory(File file) throws IOException {
		File parent = file.getParentFile();
		if (parent != null && !parent.exists()) {
			if (!parent.mkdirs()) {
				throw new IOException("Unable to create directory: " + parent);
			}
		}
	}

	private void closeQuietly() {
		try {
			if (bufferedOutputStream != null) bufferedOutputStream.close();
		} catch (Exception ignored) {
		}
		try {
			if (fos != null) fos.close();
		} catch (Exception ignored) {
		}
	}

	// -------------------------------------------------------------------------
	// Listeners
	// -------------------------------------------------------------------------
	@Override
	public void beforeStep(StepExecution stepExecution) {
		// No-op
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
		jobContext.putString("partFilePath", this.partFilePath);
		jobContext.putLong("totalRecordCount", this.recordCount);
		return stepExecution.getExitStatus();
	}
}