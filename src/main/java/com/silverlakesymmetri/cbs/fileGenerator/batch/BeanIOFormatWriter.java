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

/**
 * BeanIO-based writer supporting multiple output formats via mapping files.
 * Features:
 * - Writes to .part file first (safe for downstream consumers)
 * - Stores partFilePath + recordCount in JobExecutionContext
 * - Java 8 compatible (no NIO charset constructors)
 * - Drop-in replacement for existing jobs
 */
@Component
@StepScope
public class BeanIOFormatWriter implements OutputFormatWriter {

	private static final Logger logger = LoggerFactory.getLogger(BeanIOFormatWriter.class);

	@Autowired
	private InterfaceConfigLoader interfaceConfigLoader;

	private BeanWriter beanIOWriter;
	private Writer fileWriter;
	private StepExecution stepExecution;

	private String partFilePath;
	private long recordCount = 0;
	private boolean initialized = false;

	/**
	 * Initialize writer before step execution
	 */
	@BeforeStep
	public void beforeStep(StepExecution stepExecution) {
		this.stepExecution = stepExecution;

		String interfaceType = stepExecution.getJobParameters().getString("interfaceType");
		String outputFilePath = stepExecution.getJobParameters().getString("outputFilePath");

		try {
			init(outputFilePath, interfaceType);
		} catch (Exception e) {
			logger.error("Failed to initialize BeanIOFormatWriter", e);
			throw new IllegalStateException("BeanIOFormatWriter initialization failed", e);
		}
	}

	/**
	 * Initialize BeanIO writer and .part output file
	 */
	@Override
	public synchronized void init(String outputFilePath, String interfaceType) throws Exception {
		if (initialized) {
			return;
		}

		InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
		if (config == null) {
			throw new IllegalArgumentException("Interface configuration not found: " + interfaceType);
		}

		String mappingFile = config.getBeanioMappingFile();
		if (mappingFile == null || mappingFile.trim().isEmpty()) {
			throw new IllegalArgumentException(
					"beanioMappingFile not configured for interface: " + interfaceType);
		}

		this.partFilePath = outputFilePath + ".part";
		File outputFile = new File(partFilePath);

		if (outputFile.getParentFile() != null) {
			outputFile.getParentFile().mkdirs();
		}

		// Java 8–safe writer (charset controlled by BeanIO mapping)
		this.fileWriter = new OutputStreamWriter(
				new FileOutputStream(outputFile, false),
				StandardCharsets.UTF_8
		);

		try {
			ClassPathResource resource = new ClassPathResource("beanio/" + mappingFile);
			StreamFactory factory = StreamFactory.newInstance();
			try (InputStream in = resource.getInputStream()) {
				factory.load(in);
			}

			String streamName = interfaceType.toLowerCase() + "Stream";
			this.beanIOWriter = factory.createWriter(streamName, fileWriter);
			this.initialized = true;

			logger.info(
					"BeanIOFormatWriter initialized - interface={}, mapping={}, stream={}, output={}",
					interfaceType, mappingFile, streamName, partFilePath
			);

		} catch (Exception e) {
			logger.error("Error loading BeanIO mapping file: {}", mappingFile, e);
			throw e;
		}
	}

	/**
	 * Write records using BeanIO
	 */
	@Override
	public void write(List<? extends DynamicRecord> items) {
		if (items == null || items.isEmpty()) {
			return;
		}

		if (beanIOWriter == null) {
			throw new IllegalStateException("BeanIO writer not initialized");
		}

		for (DynamicRecord record : items) {
			try {
				beanIOWriter.write(record.asValueMap());
				recordCount++;
			} catch (Exception e) {
				logger.error("Error writing record via BeanIO", e);
				throw e;
			}
		}

		logger.debug("BeanIO wrote {} records", items.size());
	}

	/**
	 * Close writer and expose metadata to JobExecutionContext
	 */
	@Override
	public void close() throws Exception {
		try {
			if (beanIOWriter != null) {
				beanIOWriter.close();
			}

			if (fileWriter != null) {
				fileWriter.close();
			}

			if (stepExecution != null) {
				stepExecution.getJobExecution().getExecutionContext()
						.put("partFilePath", partFilePath);
				stepExecution.getJobExecution().getExecutionContext()
						.put("recordCount", recordCount);
			}

			logger.info(
					"BeanIOFormatWriter closed successfully. Records written={}, partFile={}",
					recordCount, partFilePath
			);

		} catch (Exception e) {
			logger.error("Error closing BeanIOFormatWriter", e);
			throw e;
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
