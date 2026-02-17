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
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
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
public class BeanIOFormatWriter implements OutputFormatWriter, ItemStreamWriter<DynamicRecord> {
	private static final Logger logger = LoggerFactory.getLogger(BeanIOFormatWriter.class);
	private static final String RESTART_KEY_OFFSET = "beanio.writer.byteOffset";
	private static final String RESTART_KEY_COUNT = "beanio.writer.recordCount";
	private static final Map<String, StreamFactory> FACTORY_CACHE = new ConcurrentHashMap<>();
	private final InterfaceConfigLoader interfaceConfigLoader;
	private BeanWriter beanIOWriter;
	private ByteTrackingOutputStream byteTrackingStream;
	private FileOutputStream fileOutputStream;
	private String partFilePath;
	private long recordCount;
	private String interfaceType;
	private String outputFilePath;

	@Autowired
	public BeanIOFormatWriter(InterfaceConfigLoader interfaceConfigLoader) {
		this.interfaceConfigLoader = interfaceConfigLoader;
	}

	// --- Configuration Phase (Called by DynamicItemWriter) ---

	@Override
	public void init(String outputFilePath, String interfaceType) throws Exception {
		if (outputFilePath == null || outputFilePath.trim().isEmpty()) {
			throw new IllegalArgumentException("outputFilePath must not be empty");
		}

		this.outputFilePath = outputFilePath;
		this.interfaceType = interfaceType;
		this.partFilePath = outputFilePath.endsWith(".part")
				? outputFilePath
				: outputFilePath + ".part";

		ensureDirectoryExists(this.outputFilePath);
	}

	// --- Lifecycle Phase (Called by Spring Batch) ---

	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		Assert.hasText(outputFilePath, "outputFilePath must not be empty");
		Assert.hasText(interfaceType, "interfaceType must not be empty");

		try {
			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
			if (config == null) throw new IllegalArgumentException("No config for " + interfaceType);

			File file = new File(partFilePath);
			Path parent = file.toPath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			// 1. Determine Restart State
			long lastByteOffset = 0;
			if (executionContext.containsKey(RESTART_KEY_OFFSET)) {
				lastByteOffset = executionContext.getLong(RESTART_KEY_OFFSET, 0);
				if (lastByteOffset < 0) lastByteOffset = 0;
				this.recordCount = executionContext.getLong(RESTART_KEY_COUNT, 0L);
				logger.info("Restart detected. Truncating file to byte offset: {}, records: {}", lastByteOffset, recordCount);
			}

			// 2. Open Stream and Truncate
			this.fileOutputStream = new FileOutputStream(file, false);
			FileChannel channel = this.fileOutputStream.getChannel();

			long currentSize = channel.size();

			if (lastByteOffset > currentSize) {
				logger.warn("Stored offset {} exceeds file size {}. Resetting to file size.",
						lastByteOffset, currentSize);
				lastByteOffset = currentSize;
			}

			if (lastByteOffset < currentSize) {
				channel.truncate(lastByteOffset); // CRITICAL: Cut off corrupt tail
			}
			channel.position(lastByteOffset);

			// 3. Wrap in Byte Tracker
			// We start tracking bytes from the current position (which is now lastByteOffset)
			this.byteTrackingStream = new ByteTrackingOutputStream(this.fileOutputStream, lastByteOffset);

			// 4. Initialize BeanIO
			StreamFactory factory = getOrCreateFactory(config.getBeanIoMappingFile());
			// Important: Use OutputStreamWriter with specific charset to ensure byte count matches
			Writer writer = new OutputStreamWriter(this.byteTrackingStream, StandardCharsets.UTF_8);

			this.beanIOWriter = factory.createWriter(config.getStreamName(), writer);

		} catch (Exception e) {
			throw new ItemStreamException("Failed to initialize BeanIO writer", e);
		}

		logger.info("BeanIO writer initialized for interface={}, output={}",
				interfaceType, partFilePath);
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (beanIOWriter == null) throw new IllegalStateException("Writer not opened");

		for (DynamicRecord record : items) {
			if (record != null) {
				beanIOWriter.write(record.asMap());
				recordCount++;
			}
		}

		// Note: We do NOT flush here for performance.
		// We only flush in update() to ensure the transaction boundary is safe.
	}

	@Override
	public void update(ExecutionContext executionContext) {
		try {
			if (beanIOWriter != null) {
				// Force BeanIO to flush to the underlying ByteTrackingStream
				beanIOWriter.flush();
				byteTrackingStream.flush();

				// Save current state
				executionContext.putLong(RESTART_KEY_OFFSET, byteTrackingStream.getBytesWritten());
				executionContext.putLong(RESTART_KEY_COUNT, recordCount);
			}
		} catch (Exception e) {
			throw new ItemStreamException("Failed to update restart state", e);
		}
	}

	@Override
	public void close() {
		try {
			if (beanIOWriter != null) {
				beanIOWriter.flush();
				beanIOWriter.close();
			}
		} catch (Exception e) {
			logger.warn("Error closing beanIOWriter", e);
		}
		try {
			if (fileOutputStream != null) fileOutputStream.close();
		} catch (Exception e) {
			logger.warn("Error closing fileOutputStream", e);
		}
		beanIOWriter = null;
		fileOutputStream = null;
		byteTrackingStream = null;
	}

	// --- Helper Methods ---

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

	// --- Getters ---
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

	private void ensureDirectoryExists(String path) throws Exception {
		Path parent = Paths.get(path).toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}
}