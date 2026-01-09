package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Factory for selecting appropriate output format writer.
 * Ensures all consumers use .part-aware GenericXMLWriter safely.
 */
@Component
public class OutputFormatWriterFactory {
	private static final Logger logger = LoggerFactory.getLogger(OutputFormatWriterFactory.class);

	private final InterfaceConfigLoader interfaceConfigLoader;
	private final ApplicationContext applicationContext;

	@Autowired
	public OutputFormatWriterFactory(
			InterfaceConfigLoader interfaceConfigLoader,
			ApplicationContext applicationContext) {
		this.interfaceConfigLoader = interfaceConfigLoader;
		this.applicationContext = applicationContext;
	}

	/**
	 * Select writer based on interface configuration.
	 * Priority:
	 * 1. If beanioMappingFile is configured -> Use BeanIOFormatWriter
	 * 2. Otherwise -> Use .part-aware GenericXMLWriter (safe fallback)
	 */
	public OutputFormatWriter selectWriter(String interfaceType) {
		try {
			InterfaceConfig interfaceConfig = interfaceConfigLoader.getConfig(interfaceType);

			// 1. Determine which bean type we need
			Class<? extends OutputFormatWriter> writerClass = GenericXMLWriter.class; // Default

			if (interfaceConfig != null) {
				String beanioMappingFile = interfaceConfig.getBeanioMappingFile();
				if (beanioMappingFile != null && !beanioMappingFile.isEmpty()) {
					logger.info("Selecting BeanIOFormatWriter for interface: {}", interfaceType);
					writerClass = BeanIOFormatWriter.class;
				} else {
					logger.info("Selecting GenericXMLWriter for interface: {}", interfaceType);
				}
			} else {
				logger.warn("Interface configuration not found: {}", interfaceType);
			}

			// 2. Fetch the bean from context
			// Because these are @StepScope, Spring will provide a thread-safe,
			// fresh instance for the current Step execution.
			return applicationContext.getBean(writerClass);

		} catch (Exception e) {
			logger.error("Error selecting writer for interface: {}. Falling back to a fresh GenericXMLWriter instance.", interfaceType, e);
			return applicationContext.getBean(GenericXMLWriter.class);
		}
	}
}
