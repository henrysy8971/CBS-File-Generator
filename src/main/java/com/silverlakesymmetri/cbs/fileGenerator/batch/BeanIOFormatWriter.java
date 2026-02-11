package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.beanio.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced BeanIO Writer with Append-Support (Restart Safety) and Mapping Caching.
 */
@Component
@StepScope
public class BeanIOFormatWriter implements OutputFormatWriter {
	private static final Logger logger = LoggerFactory.getLogger(BeanIOFormatWriter.class);
	private static final Map<String, StreamFactory> FACTORY_CACHE = new ConcurrentHashMap<>();
	private final InterfaceConfigLoader interfaceConfigLoader;
	private final AtomicLong recordCount = new AtomicLong(0);
	private final AtomicLong skippedCount = new AtomicLong(0);

	private BeanWriter beanIOWriter;
	private BufferedWriter bufferedWriter;
	private String partFilePath;

	@Value("${file.generation.external.config-dir:}")
	private String externalConfigDir;

	@Autowired
	public BeanIOFormatWriter(InterfaceConfigLoader interfaceConfigLoader) {
		this.interfaceConfigLoader = interfaceConfigLoader;
	}

	@Override
	public void init(String outputFilePath, String interfaceType) throws Exception {

		InterfaceConfig interfaceConfig = interfaceConfigLoader.getConfig(interfaceType);
		if (interfaceConfig == null || interfaceConfig.getBeanIoMappingFile() == null) {
			throw new IllegalArgumentException("Invalid BeanIO interfaceConfig for: " + interfaceType);
		}

		this.partFilePath = outputFilePath.endsWith(".part") ? outputFilePath : outputFilePath + ".part";
		File outputFile = new File(partFilePath);

		// RESTART LOGIC: Check if we should append
		boolean append = outputFile.exists() && outputFile.length() > 0;

		// Reset recordCount for this session
		recordCount.set(append ? countExistingRecords(outputFile, interfaceConfig.isHaveHeaders()) : 0);

		File parent = outputFile.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			throw new IOException("Failed to create directory: " + parent);
		}

		// Use BufferedWriter for significantly better I/O performance
		this.bufferedWriter = new BufferedWriter(new OutputStreamWriter(
				new FileOutputStream(outputFile, append), StandardCharsets.UTF_8));

		try {
			StreamFactory streamFactory = getOrCreateFactory(interfaceConfig.getBeanIoMappingFile());
			String streamName = interfaceConfig.getStreamName();

			this.beanIOWriter = streamFactory.createWriter(streamName, bufferedWriter);

			logger.info("BeanIO initialized [Append={}]: interface={}, file={}", append, interfaceType, partFilePath);
		} catch (Exception e) {
			close(); // Clean up on failure
			throw e;
		}
	}

	private StreamFactory getOrCreateFactory(String mappingFile) {
		return FACTORY_CACHE.computeIfAbsent(mappingFile, file -> {
			// 1. Try File System First
			if (StringUtils.hasText(externalConfigDir)) {
				String dir = externalConfigDir.trim();
				Path baseDir = Paths.get(dir).toAbsolutePath().normalize();
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

	@Override
	public void write(List<? extends DynamicRecord> items) {
		if (items == null || items.isEmpty()) return;
		if (beanIOWriter == null) throw new IllegalStateException("Writer not initialized");

		for (DynamicRecord record : items) {
			try {
				beanIOWriter.write(record.asMap());
				recordCount.incrementAndGet();
			} catch (BeanWriterException e) {
				logger.error("Failed to write record: {}", record, e);
				skippedCount.incrementAndGet();
				// Optional: Throw exception to fail job, or track separate skip count
			}
		}
	}

	@Override
	public void close() {
		try {
			if (beanIOWriter != null) beanIOWriter.close();
		} catch (Exception e) {
			logger.warn("Error closing beanIOWriter", e);
		}
		try {
			if (bufferedWriter != null) bufferedWriter.close();
		} catch (Exception e) {
			logger.warn("Error closing bufferedWriter", e);
		}
	}

	@Override
	public long getRecordCount() {
		return recordCount.get();
	}

	@Override
	public long getSkippedCount() {
		return skippedCount.get();
	}

	@Override
	public String getPartFilePath() {
		return partFilePath;
	}

	private long countExistingRecords(File file, boolean hasHeader) throws IOException {
		if (!file.exists() || file.length() == 0) return 0;

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {

			long lines = 0;
			while (reader.readLine() != null) lines++;

			if (hasHeader && lines > 0) lines--;  // subtract header

			logger.info("Existing records in file {}: {}", file.getPath(), lines);
			return lines;
		}
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
}