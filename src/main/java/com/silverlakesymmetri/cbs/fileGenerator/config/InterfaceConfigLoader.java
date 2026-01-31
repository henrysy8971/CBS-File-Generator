package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfigWrapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class InterfaceConfigLoader {
	private static final Logger logger = LoggerFactory.getLogger(InterfaceConfigLoader.class);

	@Value("classpath:interface-config.json")
	private Resource configResource;

	private final ObjectMapper objectMapper;

	/**
	 * Immutable map of interfaceType -> InterfaceConfig
	 */
	private volatile Map<String, InterfaceConfig> configs = Collections.emptyMap();
	private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList("xml", "csv", "txt", "json", "dat"));

	public InterfaceConfigLoader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	public void loadConfigs() {
		if (!configResource.exists()) {
			throw new IllegalStateException("interface-config.json not found on classpath");
		}

		try (InputStream is = configResource.getInputStream()) {
			InterfaceConfigWrapper wrapper = objectMapper.readValue(is, InterfaceConfigWrapper.class);
			Map<String, InterfaceConfig> loaded = wrapper.getInterfaces();

			// 1. Perform strict validation
			validateConfigs(loaded);
			applyDefaults(loaded);

			this.configs = Collections.unmodifiableMap(loaded);
			logSummary(this.configs);
		} catch (Exception e) {
			logger.error("Failed to load interface-config.json", e);
			throw new IllegalStateException("Startup failed: Invalid interface configuration");
		}
	}

	/* ================= Public API ================= */
	public InterfaceConfig getConfig(String interfaceType) {
		InterfaceConfig config = configs.get(interfaceType);
		if (config == null) {
			throw new IllegalArgumentException("No interface configuration found for: " + interfaceType);
		}
		return config;
	}

	public boolean interfaceExists(String interfaceType) {
		return configs.containsKey(interfaceType);
	}

	public Map<String, InterfaceConfig> getAllConfigs() {
		return configs;
	}

	public Map<String, InterfaceConfig> getEnabledConfigs() {
		return configs.entrySet()
				.stream()
				.filter(e -> e.getValue().isEnabled())
				.collect(Collectors.toMap(
						Map.Entry::getKey,
						Map.Entry::getValue
				));
	}

	/* ================= Validation ================= */
	private void validateConfigs(Map<String, InterfaceConfig> configs) {
		if (configs.isEmpty()) {
			throw new IllegalStateException("No interfaces defined in interface-config.json");
		}

		configs.forEach((key, cfg) -> {
			// A. Check for Required Fields
			if (StringUtils.isBlank(cfg.getDataSourceQuery())) {
				throw new IllegalStateException("Config Error [" + key + "]: 'dataSourceQuery' is required");
			}

			// B. Validate File Extension
			String ext = cfg.getOutputFileExtension();
			if (StringUtils.isBlank(ext)) {
				throw new IllegalStateException("Config Error [" + key + "]: 'outputFileExtension' is required");
			}

			if (!ALLOWED_EXTENSIONS.contains(ext.toLowerCase())) {
				throw new IllegalStateException("Config Error [" + key + "]: Invalid extension '" + ext +
						"'. Allowed: " + ALLOWED_EXTENSIONS);
			}

			// C. Validate Chunk Size
			if (cfg.getChunkSize() == null || cfg.getChunkSize() <= 0 || cfg.getChunkSize() > 10000) {
				throw new IllegalStateException("Config Error [" + key + "]: 'chunkSize' must be between 1 and 10000");
			}

			// D. Metadata Consistency
			if ("XML".equalsIgnoreCase(cfg.getOutputFormat().name()) && StringUtils.isBlank(cfg.getXsdSchemaFile())) {
				logger.warn("Config Warning [{}]: XML format selected but no XSD schema provided for validation", key);
			}
		});
	}

	/* ================= Defaults ================= */
	private void applyDefaults(Map<String, InterfaceConfig> configs) {
		configs.forEach((key, cfg) -> {
			if (cfg.getName() == null || cfg.getName().trim().isEmpty()) {
				cfg.setName(key);
			}
		});
	}

	/* ================= Logging ================= */
	private void logSummary(Map<String, InterfaceConfig> configs) {
		logger.info("Loaded {} interface configurations", configs.size());

		configs.forEach((key, cfg) ->
				logger.info(
						"Interface [{}] enabled={} format={} chunk={} schema={}",
						key,
						cfg.isEnabled(),
						cfg.getOutputFormat(),
						cfg.getChunkSize(),
						cfg.getXsdSchemaFile()
				)
		);
	}
}
