package com.silverlakesymmetri.cbs.fileGenerator.batch.custom.orders;

import com.silverlakesymmetri.cbs.fileGenerator.dto.order.OrderDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

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
	private static final String RESTART_KEY_OFFSET = "order.writer.byteOffset";
	private static final String RESTART_KEY_COUNT = "order.writer.recordCount";

	// XML Constants
	private static final String NS_URI = "http://www.example.com/order";
	private static final String NS_PREFIX = "tns";
	private static final String ROOT_TAG = "orderInterface";
	private static final String WRAPPER_TAG = "orders";
	private static final String ITEM_TAG = "order";

	private final Jaxb2Marshaller marshaller;

	// Streams & State
	private XMLStreamWriter xmlStreamWriter;
	private ByteTrackingOutputStream byteTrackingStream;
	private BufferedOutputStream bufferedOutputStream;
	private FileOutputStream fileOutputStream;

	private final String partFilePath;
	private boolean stepSuccessful = false;

	private long recordCount = 0;
	private final Object lock = new Object();

	@Autowired
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

	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		Assert.hasText(partFilePath, "outputFilePath must not be empty");

		try {
			File file = new File(partFilePath);
			ensureParentDirectory(file);

			long lastByteOffset = 0;
			boolean isRestart = false;

			if (executionContext.containsKey(RESTART_KEY_OFFSET)) {
				lastByteOffset = executionContext.getLong(RESTART_KEY_OFFSET, 0L);
				if (lastByteOffset < 0) lastByteOffset = 0;
				this.recordCount = executionContext.getLong(RESTART_KEY_COUNT, 0L);
				logger.info("Restart detected. Truncating file to byte offset: {}, records: {}", lastByteOffset, recordCount);
				isRestart = true;
			}

			this.fileOutputStream = new FileOutputStream(file, isRestart);
			FileChannel channel = this.fileOutputStream.getChannel();

			// Truncate if necessary (Critical for restart safety)
			if (isRestart) {
				if (lastByteOffset < channel.size()) {
					channel.truncate(lastByteOffset);
					logger.info("Restart detected. Truncating file to offset {}, resuming from record {}", lastByteOffset, recordCount);
				}
			} else {
				// Fresh run: Truncate to 0 to overwrite any existing garbage
				channel.truncate(0);
			}

			// Force channel to the end (which is now 'offset' or 0)
			// This ensures the byte tracker starts at the exact physical end of file
			long actualPosition = channel.size();

			this.byteTrackingStream = new ByteTrackingOutputStream(this.fileOutputStream, actualPosition);
			this.bufferedOutputStream = new BufferedOutputStream(this.byteTrackingStream);
			this.xmlStreamWriter = XMLOutputFactory.newInstance()
					.createXMLStreamWriter(new OutputStreamWriter(this.bufferedOutputStream, StandardCharsets.UTF_8));

			if (!isRestart) {
				writeHeader();
			}

		} catch (Exception e) {
			closeQuietly();
			throw new ItemStreamException("Failed to open OrderItemWriter", e);
		}
	}

	@Override
	public void write(List<? extends OrderDto> items) throws Exception {
		if (this.xmlStreamWriter == null) throw new IllegalStateException("Writer not opened");
		if (items == null || items.isEmpty()) return;

		synchronized (lock) { // ensure thread-safe writes
			for (OrderDto order : items) {
				if (order == null) continue;
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

		logger.debug("Chunk written: {}, total written: {}", items.size(), recordCount);
	}

	@Override
	public void update(ExecutionContext executionContext) {
		try {
			// Flush cascade: XML -> Buffer -> ByteTracker -> Disk
			if (xmlStreamWriter != null) xmlStreamWriter.flush();
			if (bufferedOutputStream != null) bufferedOutputStream.flush();
			if (byteTrackingStream != null) {
				long currentOffset = byteTrackingStream.getBytesWritten();
				executionContext.putLong(RESTART_KEY_OFFSET, currentOffset);
				executionContext.putLong(RESTART_KEY_COUNT, recordCount);
				logger.debug("Saved restart state: bytes={}, records={}", currentOffset, recordCount);
			}
		} catch (Exception e) {
			throw new ItemStreamException("Failed to update ExecutionContext", e);
		}
	}

	@Override
	public void close() {
		synchronized (lock) {
			try {
				if (xmlStreamWriter != null) {
					if (stepSuccessful) {
						writeFooter();
						logger.info("Footer written. XML completed.");
					} else {
						logger.warn("Step failed. Footer NOT written to allow safe restart.");
					}
					xmlStreamWriter.flush();
					xmlStreamWriter.close();
					xmlStreamWriter = null;
				}
			} catch (Exception e) {
				logger.error("Error closing XML writer", e);
			} finally {
				closeQuietly();
			}
		}
	}

	// -------------------------------------------------------------------------
	// Listeners
	// -------------------------------------------------------------------------
	@Override
	public void beforeStep(StepExecution stepExecution) {
		this.stepSuccessful = false;
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		this.stepSuccessful = (stepExecution.getStatus() == BatchStatus.COMPLETED);
		ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
		jobContext.putString("partFilePath", this.partFilePath);
		jobContext.putLong("totalRecordCount", this.recordCount);
		return stepExecution.getExitStatus();
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
		if (bufferedOutputStream != null) bufferedOutputStream.flush();
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
			if (xmlStreamWriter != null) xmlStreamWriter.close();
		} catch (Exception ignored) {
		}
		try {
			if (bufferedOutputStream != null) bufferedOutputStream.close();
		} catch (Exception ignored) {
		}
		try {
			if (fileOutputStream != null) fileOutputStream.close();
		} catch (Exception ignored) {
		}
		xmlStreamWriter = null;
		bufferedOutputStream = null;
		fileOutputStream = null;
	}

	// --------------------------------------------------
	// Inner Class: Byte Tracking
	// --------------------------------------------------
	private static class ByteTrackingOutputStream extends OutputStream {
		private final OutputStream delegate;
		private long bytesWritten;

		public ByteTrackingOutputStream(OutputStream delegate, long initialOffset) {
			this.delegate = delegate;
			this.bytesWritten = initialOffset;
		}

		@Override
		public void write(int b) throws IOException {
			delegate.write(b);
			bytesWritten++;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			delegate.write(b, off, len);
			bytesWritten += len;
		}

		@Override
		public void write(byte[] b) throws IOException {
			delegate.write(b);
			bytesWritten += b.length;
		}

		@Override
		public void flush() throws IOException {
			delegate.flush();
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}

		public long getBytesWritten() {
			return bytesWritten;
		}
	}
}