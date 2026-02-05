package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.beanio.BeanWriter;
import org.beanio.BeanWriterException;
import org.beanio.StreamFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

	private BeanWriter beanIOWriter;
	private BufferedWriter bufferedWriter;
	private String partFilePath;

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
			ClassPathResource resource = new ClassPathResource("beanio/" + file);

			if (!resource.exists()) {
				throw new IllegalArgumentException("BeanIO mapping file not found: " + resource.getPath());
			}

			try (InputStream in = resource.getInputStream()) {
				StreamFactory factory = StreamFactory.newInstance();
				factory.load(in);
				return factory;
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
			} catch (BeanWriterException e) {
				logger.error("Failed to write record: {}", record, e);
			}
		}

		recordCount.addAndGet(items.size());
	}

	@Override
	public void close() throws Exception {
		try {
			if (beanIOWriter != null) beanIOWriter.close();
			if (bufferedWriter != null) bufferedWriter.close();
			logger.info("BeanIO writer closed. Total records this session: {}", recordCount);
		} finally {
			this.beanIOWriter = null;
			this.bufferedWriter = null;
		}
	}

	@Override
	public long getRecordCount() {
		return recordCount.get();
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
}