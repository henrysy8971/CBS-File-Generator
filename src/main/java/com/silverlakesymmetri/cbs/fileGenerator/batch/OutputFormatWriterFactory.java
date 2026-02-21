package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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

	public OutputFormatWriter selectWriter(String interfaceType) {
		InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);

		if (config == null) {
			logger.warn("No config found for {}. Defaulting to GenericXMLWriter.", interfaceType);
			return applicationContext.getBean(GenericXMLWriter.class);
		}

		Class<? extends OutputFormatWriter> writerClass = determineWriterClass(config);
		logger.info("Interface: {} | Format: {} | Writer: {}",
				interfaceType, config.getOutputFormat(), writerClass.getSimpleName());

		try {
			return applicationContext.getBean(writerClass);
		} catch (Exception e) {
			logger.error("Failed to instantiate {}. Falling back to XML.", writerClass.getName(), e);
			return applicationContext.getBean(GenericXMLWriter.class);
		}
	}

	private Class<? extends OutputFormatWriter> determineWriterClass(InterfaceConfig config) {
		String format = config.getOutputFormat().name();

		// 1. Explicit JSON Check
		if ("JSON".equalsIgnoreCase(format)) {
			return GenericJSONWriter.class;
		}

		// 2. Explicit XML Check
		if ("XML".equalsIgnoreCase(format)) {
			return GenericXMLWriter.class;
		}

		// 3. BeanIO Fallback (CSV/Fixed-Length)
		if (StringUtils.hasText(config.getBeanIoMappingFile())) {
			return GenericBeanIOWriter.class;
		}

		// 4. Ultimate Fallback
		return GenericXMLWriter.class;
	}
}