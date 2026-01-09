package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Factory for selecting appropriate output format writer.
 * Ensures all consumers use .part-aware GenericXMLWriter safely.
 */
@Component
public class OutputFormatWriterFactory {
	private static final Logger logger = LoggerFactory.getLogger(OutputFormatWriterFactory.class);

	private final InterfaceConfigLoader interfaceConfigLoader;
	private final BeanIOFormatWriter beanIOFormatWriter;
	private final GenericXMLWriter genericXMLWriter;

	@Autowired
	public OutputFormatWriterFactory(
			InterfaceConfigLoader interfaceConfigLoader,
			BeanIOFormatWriter beanIOFormatWriter,
			GenericXMLWriter genericXMLWriter) {
		this.interfaceConfigLoader = interfaceConfigLoader;
		this.beanIOFormatWriter = beanIOFormatWriter;
		this.genericXMLWriter = genericXMLWriter;
	}

	/**
	 * Select writer based on interface configuration.
	 * Priority:
	 * 1. If beanioMappingFile is configured -> Use BeanIOFormatWriter
	 * 2. Otherwise -> Use .part-aware GenericXMLWriter (safe fallback)
	 */
	public OutputFormatWriter selectWriter(String interfaceType) {
		try {
			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);

			if (config == null) {
				logger.warn("Interface configuration not found: {}", interfaceType);
				return genericXMLWriter; // always .part-aware
			}

			String beanioMappingFile = config.getBeanioMappingFile();
			if (beanioMappingFile != null && !beanioMappingFile.isEmpty()) {
				logger.info("Using BeanIOFormatWriter for interface: {} (mapping: {})",
						interfaceType, beanioMappingFile);
				return beanIOFormatWriter;
			}

			logger.info("Using .part-aware GenericXMLWriter for interface: {} (no mapping configured)",
					interfaceType);
			return genericXMLWriter;

		} catch (Exception e) {
			logger.error("Error selecting writer for interface: {}", interfaceType, e);
			logger.warn("Falling back to .part-aware GenericXMLWriter");
			return genericXMLWriter;
		}
	}
}
