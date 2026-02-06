package com.silverlakesymmetri.cbs.fileGenerator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfigWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.*;

@Component
public class InterfaceConfigLoader {
	private static final Logger logger = LoggerFactory.getLogger(InterfaceConfigLoader.class);

	@Value("classpath:interface-config.json")
	private Resource configResource;

	private final ObjectMapper objectMapper;
	private final Object reloadLock = new Object(); // Lock for synchronization

	/**
	 * Immutable map of interfaceType -> InterfaceConfig
	 * Volatile ensures that once 'configs' is updated, all threads see the new map immediately.
	 */
	private volatile Map<String, InterfaceConfig> configs = Collections.emptyMap();

	public InterfaceConfigLoader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	public void init() {
		// Load on startup. If this fails, the app should crash (Fail-Fast).
		try {
			refreshConfigs();
		} catch (Exception e) {
			logger.error("CRITICAL: Failed to load initial configuration.", e);
			throw new IllegalStateException("Startup failed: Invalid interface configuration", e);
		}
	}

	/**
	 * Public method called by AdminController to hot-reload settings.
	 * Synchronized to prevent multiple admins from triggering a race condition.
	 */
	public void refreshConfigs() {
		synchronized (reloadLock) {
			if (configResource == null || !configResource.exists()) {
				throw new IllegalStateException("interface-config.json not found on classpath");
			}

			logger.info("Reloading interface configurations from classpath resource [{}]", configResource.getFilename());

			try (InputStream is = configResource.getInputStream()) {
				InterfaceConfigWrapper wrapper = objectMapper.readValue(is, InterfaceConfigWrapper.class);
				if (wrapper == null || wrapper.getInterfaces() == null || wrapper.getInterfaces().isEmpty()) {
					// If reloading, we might want to keep the old config instead of crashing,
					// but throwing here alerts the admin that the file is empty.
					throw new IllegalStateException("No interfaces defined in interface-config.json");
				}

				Map<String, InterfaceConfig> loaded = wrapper.getInterfaces();
				Map<String, InterfaceConfig> normalized =
						loaded.entrySet().stream()
								.collect(Collectors.toMap(
										e -> e.getKey().trim(),
										Map.Entry::getValue
								));

				if (normalized.size() != loaded.size()) {
					throw new IllegalStateException("Duplicate interface keys after normalization");
				}

				// 1. Perform strict validation
				validateConfigs(normalized);
				applyDefaults(normalized);

				// 2. Atomic Swap
				// This assignment is atomic. Threads reading 'configs' will either see
				// the old map or the completely new map, never a partial state.
				this.configs = Collections.unmodifiableMap(normalized);

				logSummary(this.configs);
			} catch (Exception e) {
				// If this was a manual reload, rethrow so the Controller can return 500 Error
				// and the old config remains active.
				logger.error("Failed to load interface-config.json", e);
				throw new IllegalStateException("Failed to load interface-config.json", e);
			}
		}
	}

	/* ================= Public API ================= */
	public InterfaceConfig getConfig(String interfaceType) {
		String key = interfaceType == null ? "" : interfaceType.trim();
		if (key.isEmpty()) {
			throw new IllegalArgumentException("interfaceType cannot be null or empty");
		}
		InterfaceConfig config = configs.get(key);
		if (config == null) {
			throw new IllegalArgumentException("No interface configuration found for: " + interfaceType);
		}
		return config;
	}

	public boolean interfaceExists(String interfaceType) {
		String key = interfaceType == null ? "" : interfaceType.trim();
		return !key.isEmpty() && configs.containsKey(key);
	}

	public Map<String, InterfaceConfig> getAllConfigs() {
		return configs;
	}

	public Map<String, InterfaceConfig> getEnabledConfigs() {
		return configs.entrySet()
				.stream()
				.filter(e -> e.getValue().isEnabled())
				.collect(Collectors.collectingAndThen(
						Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue),
						Collections::unmodifiableMap
				));
	}

	/* ================= Validation ================= */
	private void validateConfigs(Map<String, InterfaceConfig> configs) {
		if (configs == null || configs.isEmpty()) {
			throw new IllegalStateException("No interfaces defined in interface-config.json");
		}

		configs.forEach((key, cfg) -> {
			if (key.isEmpty()) {
				throw new IllegalStateException("Interface key cannot be null or empty");
			}

			if (cfg == null) {
				throw new IllegalStateException("Config [" + key + "] is null");
			}

			// A. Check for Required Fields
			if (cfg.isDynamic()) {
				if (cfg.getDataSourceQuery() == null || cfg.getDataSourceQuery().trim().isEmpty()) {
					throw new IllegalStateException("Config Error [" + key + "]: 'dataSourceQuery' is required");
				}
			}

			// B. Validate File Extension
			String ext = cfg.getOutputFileExtension();
			if (ext == null || ext.trim().isEmpty()) {
				throw new IllegalStateException("Config Error [" + key + "]: 'outputFileExtension' is required");
			}

			if (!ALLOWED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
				throw new IllegalStateException("Config Error [" + key + "]: Invalid extension '" + ext +
						"'. Allowed: " + ALLOWED_EXTENSIONS);
			}

			// C. Validate Chunk Size
			if (cfg.getChunkSize() == null || cfg.getChunkSize() < MIN_CHUNK_SIZE || cfg.getChunkSize() > MAX_CHUNK_SIZE) {
				throw new IllegalStateException(
						String.format(
								"Config Error [%s]: 'chunkSize' must be between %d and %d",
								key, MIN_CHUNK_SIZE, MAX_CHUNK_SIZE
						)
				);
			}

			// D. Metadata Consistency
			if (cfg.getOutputFormat() == InterfaceConfig.OutputFormat.XML &&
					(cfg.getXsdSchemaFile() == null || cfg.getXsdSchemaFile().trim().isEmpty())) {
				logger.warn("Config [{}]: XML format selected but no XSD schema provided for validation", key);
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
