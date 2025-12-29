package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.beanio.BeanWriter;
import org.beanio.StreamFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced BeanIO Writer with Append-Support (Restart Safety) and Mapping Caching.
 */
@Component
@StepScope
public class BeanIOFormatWriter implements OutputFormatWriter {

	private static final Logger logger = LoggerFactory.getLogger(BeanIOFormatWriter.class);

	// Cache StreamFactories to avoid expensive XML parsing on every step
	private static final Map<String, StreamFactory> FACTORY_CACHE = new ConcurrentHashMap<>();

	@Autowired
	private InterfaceConfigLoader interfaceConfigLoader;

	private BeanWriter beanIOWriter;
	private BufferedWriter bufferedWriter;
	private StepExecution stepExecution;

	private String partFilePath;
	private long recordCount = 0;
	private boolean initialized = false;

	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.stepExecution = stepExecution;
		// Logic handled via OutputFormatWriter.init() call from DynamicItemWriter
	}

	@Override
	public synchronized void init(String outputFilePath, String interfaceType) throws Exception {
		if (initialized) return;

		InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
		if (config == null || config.getBeanioMappingFile() == null) {
			throw new IllegalArgumentException("Invalid BeanIO config for: " + interfaceType);
		}

		this.partFilePath = outputFilePath.endsWith(".part") ? outputFilePath : outputFilePath + ".part";
		File outputFile = new File(partFilePath);

		// RESTART LOGIC: Check if we should append
		boolean append = outputFile.exists() && outputFile.length() > 0;
		if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();

		// Use BufferedWriter for significantly better I/O performance
		this.bufferedWriter = new BufferedWriter(new OutputStreamWriter(
				new FileOutputStream(outputFile, append), StandardCharsets.UTF_8));

		try {
			StreamFactory factory = getOrCreateFactory(config.getBeanioMappingFile());
			String streamName = interfaceType.toLowerCase() + "Stream";

			this.beanIOWriter = factory.createWriter(streamName, bufferedWriter);
			this.initialized = true;

			logger.info("BeanIO initialized [Append={}]: interface={}, file={}", append, interfaceType, partFilePath);
		} catch (Exception e) {
			close(); // Clean up on failure
			throw e;
		}
	}

	private StreamFactory getOrCreateFactory(String mappingFile) throws IOException {
		return FACTORY_CACHE.computeIfAbsent(mappingFile, file -> {
			try {
				StreamFactory factory = StreamFactory.newInstance();
				factory.load(new ClassPathResource("beanio/" + file).getInputStream());
				return factory;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (items == null || items.isEmpty()) return;
		if (beanIOWriter == null) throw new IllegalStateException("Writer not initialized");

		for (DynamicRecord record : items) {
			// BeanIO is excellent at mapping Maps to Flat Files/CSV/XML
			beanIOWriter.write(record.asValueMap());
			recordCount++;
		}

		// Periodically flush to disk to ensure data is persisted in case of crash
		bufferedWriter.flush();
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
			this.initialized = false;
		}
	}

	@Override
	public long getRecordCount() {
		return recordCount;
	}

	@Override
	public String getPartFilePath() {
		return partFilePath;
	}
}