package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.beanio.BeanIOConfigurationException;
import org.beanio.BeanIOException;
import org.beanio.BeanWriter;
import org.beanio.StreamFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced BeanIO Writer with Append-Support (Restart Safety) and Mapping Caching.
 */
@Component
@StepScope
public class BeanIOFormatWriter implements OutputFormatWriter, StepExecutionListener {
	private static final Logger logger = LoggerFactory.getLogger(BeanIOFormatWriter.class);
	private static final String RESTART_KEY_OFFSET = "beanio.writer.byteOffset";
	private static final String RESTART_KEY_COUNT = "beanio.writer.recordCount";
	private static final Map<String, StreamFactory> FACTORY_CACHE = new ConcurrentHashMap<>();
	private final InterfaceConfigLoader interfaceConfigLoader;

	private BeanWriter beanIOWriter;
	private ByteTrackingOutputStream byteTrackingStream;
	private BufferedOutputStream bufferedOutputStream;
	private FileOutputStream fileOutputStream;

	private String partFilePath;
	private String interfaceType;
	private String outputFilePath;

	private long recordCount = 0;
	private boolean stepSuccessful = false;

	private final Object lock = new Object(); // Thread-safety for write operations

	@Autowired
	public BeanIOFormatWriter(InterfaceConfigLoader interfaceConfigLoader) {
		this.interfaceConfigLoader = interfaceConfigLoader;
	}

	// --------------------------------------------------
	// Initialization
	// --------------------------------------------------

	@Override
	public void init(String outputFilePath, String interfaceType) throws Exception {
		if (outputFilePath == null || outputFilePath.trim().isEmpty()) {
			throw new IllegalArgumentException("outputFilePath must not be empty");
		}

		this.outputFilePath = outputFilePath;

		this.interfaceType = (interfaceType != null && !interfaceType.trim().isEmpty())
				? interfaceType.trim()
				: null;

		this.partFilePath = outputFilePath.endsWith(".part")
				? outputFilePath
				: outputFilePath + ".part";

		ensureDirectoryExists(this.outputFilePath);
	}

	// --------------------------------------------------
	// Restart Handling
	// --------------------------------------------------
	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		Assert.hasText(outputFilePath, "outputFilePath must not be empty");
		Assert.hasText(interfaceType, "interfaceType must not be empty");

		try {
			File file = new File(partFilePath);

			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
			if (config == null) throw new IllegalArgumentException("No config for " + interfaceType);

			long lastByteOffset = 0;
			boolean isRestart = false;

			if (executionContext.containsKey(RESTART_KEY_OFFSET)) {
				lastByteOffset = executionContext.getLong(RESTART_KEY_OFFSET, 0L);
				if (lastByteOffset < 0) lastByteOffset = 0;
				this.recordCount = executionContext.getLong(RESTART_KEY_COUNT, 0L);
				logger.info("Restart detected. Truncating file to byte offset: {}, records: {}", lastByteOffset, recordCount);
				isRestart = true;
			}

			this.fileOutputStream = new FileOutputStream(file, true);
			FileChannel channel = this.fileOutputStream.getChannel();

			// Truncate if necessary (Critical for restart safety)
			if (isRestart) {
				if (lastByteOffset < channel.size()) {
					channel.truncate(lastByteOffset);
					logger.info("Restart: Truncated file to offset {}, resuming record {}", lastByteOffset, recordCount);
				}
			} else {
				// Fresh run: Truncate to 0 to overwrite any existing garbage
				channel.truncate(0);
			}

			// Force channel to the end (which is now 'offset' or 0)
			// This ensures the byte tracker starts at the exact physical end of file
			long actualPosition = channel.size();

			this.byteTrackingStream = new ByteTrackingOutputStream(this.fileOutputStream, actualPosition);
			bufferedOutputStream = new BufferedOutputStream(byteTrackingStream);
			StreamFactory factory = getOrCreateFactory(config.getBeanIoMappingFile());
			this.beanIOWriter = factory.createWriter(config.getStreamName(),
					new OutputStreamWriter(bufferedOutputStream, StandardCharsets.UTF_8));

			if (!isRestart) {
				writeHeader();
			}
		} catch (Exception e) {
			closeQuietly();
			throw new ItemStreamException("Failed to initialize BeanIO writer", e);
		}

		logger.info("BeanIO writer initialized for interface={}, output={}",
				interfaceType, partFilePath);
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (beanIOWriter == null) throw new IllegalStateException("Writer not opened");
		if (items == null || items.isEmpty()) return;

		synchronized (lock) { // ensure thread-safe writes
			for (DynamicRecord record : items) {
				if (record != null) {
					beanIOWriter.write(record.asMap());
					recordCount++;
				}
			}
		}

		logger.debug("Chunk written: {} records, total written: {}", items.size(), recordCount);
	}

	@Override
	public void update(ExecutionContext executionContext) {
		try {
			if (beanIOWriter != null) beanIOWriter.flush();
			if (bufferedOutputStream != null) bufferedOutputStream.flush();
			if (byteTrackingStream != null) {
				long currentOffset = byteTrackingStream.getBytesWritten();
				executionContext.putLong(RESTART_KEY_OFFSET, currentOffset);
				executionContext.putLong(RESTART_KEY_COUNT, recordCount);
				logger.debug("Saved restart state: bytes={}, records={}", currentOffset, recordCount);
			}
		} catch (Exception e) {
			throw new ItemStreamException("Failed to update restart state", e);
		}
	}

	@Override
	public void close() {
		synchronized (lock) {
			try {
				if (beanIOWriter != null) {
					if (stepSuccessful) {
						writeFooter();
						logger.info("Footer written. File completed.");
					} else {
						logger.warn("Step failed. Footer NOT written to allow safe restart.");
					}
					beanIOWriter.flush();
					beanIOWriter.close();
				}
			} catch (Exception e) {
				logger.warn("Failed closing BeanIO Writer", e);
			} finally {
				closeQuietly();
			}
		}
	}

	// --------------------------------------------------
	// Listeners
	// --------------------------------------------------
	@Override
	public void beforeStep(StepExecution stepExecution) {
		this.stepSuccessful = false;
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		this.stepSuccessful = (stepExecution.getStatus() == BatchStatus.COMPLETED);
		return null;
	}

	@Override
	public long getRecordCount() {
		return recordCount;
	}

	@Override
	public long getSkippedCount() {
		return 0;
	}

	@Override
	public String getOutputFilePath() {
		return partFilePath;
	}

	private void closeQuietly() {
		try {
			if (beanIOWriter != null) beanIOWriter.close();
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
		beanIOWriter = null;
		bufferedOutputStream = null;
		fileOutputStream = null;
	}

	private void writeHeader() {
	}

	private void writeFooter() {
	}

	// --------------------------------------------------
	// Helpers
	// --------------------------------------------------

	private StreamFactory getOrCreateFactory(String mappingFile) {
		return FACTORY_CACHE.computeIfAbsent(mappingFile, file -> {
			// 1. Try File System First
			if (StringUtils.hasText(outputFilePath)) {
				Path outputPath = Paths.get(outputFilePath)
						.toAbsolutePath()
						.normalize();

				Path baseDir = outputPath.getParent();
				if (baseDir == null) {
					baseDir = Paths.get("").toAbsolutePath();
				}

				Path externalPath = baseDir.resolve("beanio").resolve(file).normalize();

				if (!externalPath.startsWith(baseDir)) {
					logger.warn("BeanIO mapping file path traversal attempt blocked: {}", file);
				} else if (Files.exists(externalPath, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(externalPath)) {
					try (InputStream is = Files.newInputStream(externalPath)) {
						return loadFactoryFromStream(is, externalPath.toString());
					} catch (IllegalStateException ignored) {
					} catch (IOException e) {
						logger.warn("Failed to get external path input stream: {}", externalPath, e);
					}
				}
			}

			// 2. Fallback to Classpath
			ClassPathResource resource = new ClassPathResource("beanio/" + file);

			if (!resource.exists() || !resource.isReadable()) {
				throw new IllegalArgumentException("BeanIO mapping file not found / not readable: " + resource.getPath());
			}

			try (InputStream is = resource.getInputStream()) {
				return loadFactoryFromStream(is, resource.getPath());
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to load BeanIO mapping file: " + resource.getPath(), e);
			}
		});
	}

	private StreamFactory loadFactoryFromStream(InputStream is, String filePath) {
		StreamFactory factory = StreamFactory.newInstance();
		try {
			factory.load(is);
			return factory;
		} catch (BeanIOConfigurationException e) {
			logger.error("BeanIO configuration error while loading mapping file: {}", filePath, e);
		} catch (BeanIOException e) {
			logger.error("BeanIO I/O error while parsing mapping file: {}", filePath, e);
		} catch (IOException e) {
			logger.error("I/O error while reading mapping file stream: {}", filePath, e);
		}
		throw new IllegalStateException("Failed to load BeanIO mapping file: " + filePath);
	}

	/**
	 * Clear cached factory (useful for tests or hot reload scenarios).
	 */
	public static void clearFactoryCache() {
		FACTORY_CACHE.clear();
		logger.info("BeanIO Factory Cache cleared.");
	}

	private void ensureDirectoryExists(String path) throws IOException {
		Path parent = Paths.get(path).toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
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