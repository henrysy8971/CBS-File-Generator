package com.silverlakesymmetri.cbs.fileGenerator.service;

import com.silverlakesymmetri.cbs.fileGenerator.entity.AppConfig;
import com.silverlakesymmetri.cbs.fileGenerator.repository.AppConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AppConfigService {
	private static final Logger logger = LoggerFactory.getLogger(AppConfigService.class);

	private final AppConfigRepository repository;

	private final Map<String, String> cache = new ConcurrentHashMap<>();
	private LocalDateTime lastRefresh = LocalDateTime.MIN;

	public AppConfigService(AppConfigRepository repository) {
		this.repository = repository;
	}

	@PostConstruct
	public void initCache() {
		List<AppConfig> configs = repository.findByActiveTrue();
		configs.forEach(cfg -> cache.put(cfg.getConfigKey(), cfg.getConfigValue()));
		lastRefresh = LocalDateTime.now();
		logger.info("AppConfig cache initialized with {} entries", cache.size());
	}

	/**
	 * Cache-first read with fallback to DB
	 */
	public Optional<String> getConfigValue(String key) {
		String value = cache.get(key);
		if (value != null) {
			return Optional.of(value);
		}

		// fallback to DB
		Optional<AppConfig> dbValue = repository.findByConfigKey(key)
				.filter(AppConfig::isActive);
		dbValue.ifPresent(cfg -> cache.put(cfg.getConfigKey(), cfg.getConfigValue()));
		return dbValue.map(AppConfig::getConfigValue);
	}

	/**
	 * Save or update a config
	 */
	@Transactional
	public AppConfig saveConfig(String key, String value, String type, String description) {
		AppConfig config = repository.findByConfigKey(key).orElse(new AppConfig(key, value));
		config.setConfigValue(value);
		config.setConfigType(type);
		config.setDescription(description);
		config.setActive(true);
		config.setUpdatedDate(LocalDateTime.now());
		if (config.getCreatedDate() == null) {
			config.setCreatedDate(LocalDateTime.now());
		}

		AppConfig saved = repository.save(config);
		cache.put(saved.getConfigKey(), saved.getConfigValue());
		logger.info("Configuration saved: key={}, value={}", saved.getConfigKey(), saved.getConfigValue());
		return saved;
	}

	/**
	 * Disable a config
	 */
	@Transactional
	public void disableConfig(String key) {
		repository.findByConfigKey(key).ifPresent(cfg -> {
			cfg.setActive(false);
			cfg.setUpdatedDate(LocalDateTime.now());
			repository.save(cfg);
			cache.remove(key);
			logger.info("Configuration disabled: key={}", key);
		});
	}

	/**
	 * Partial refresh: only update configs changed since last refresh
	 */
	@Transactional
	@Scheduled(fixedDelayString = "${app.config.cache.refresh-interval-ms:300000}") // default 5 min
	public void refreshCache() {
		LocalDateTime startTime = LocalDateTime.now(); // Capture start time

		// Get all changes (both newly active and newly inactive)
		List<AppConfig> changes = repository.findByActiveTrueAndUpdatedDateAfter(lastRefresh);

		for (AppConfig cfg : changes) {
			if (cfg.isActive()) {
				cache.put(cfg.getConfigKey(), cfg.getConfigValue());
			} else {
				cache.remove(cfg.getConfigKey());
			}
		}

		lastRefresh = startTime;
		logger.info("AppConfig partial cache refresh completed: {} updated configs", changes.size());
	}

	/**
	 * Return all active configs
	 */
	public Map<String, String> getAllActiveConfigs() {
		return Collections.unmodifiableMap(cache);
	}
}
