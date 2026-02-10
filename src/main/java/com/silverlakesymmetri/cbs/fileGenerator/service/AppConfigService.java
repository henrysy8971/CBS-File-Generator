package com.silverlakesymmetri.cbs.fileGenerator.service;

import com.silverlakesymmetri.cbs.fileGenerator.entity.AppConfig;
import com.silverlakesymmetri.cbs.fileGenerator.repository.AppConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AppConfigService {
	private static final Logger logger = LoggerFactory.getLogger(AppConfigService.class);
	private final Map<String, String> cache = new ConcurrentHashMap<>();
	private final AppConfigRepository appConfigRepository;
	private Timestamp lastRefresh = new Timestamp(0L);

	@Autowired
	public AppConfigService(AppConfigRepository appConfigRepository) {
		this.appConfigRepository = appConfigRepository;
	}

	@PostConstruct
	public void initCache() {
		List<AppConfig> configs = appConfigRepository.findByActiveTrue();
		configs.forEach(cfg -> cache.put(cfg.getConfigKey(), cfg.getConfigValue()));
		lastRefresh = new Timestamp(System.currentTimeMillis());
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
		Optional<AppConfig> dbValue = appConfigRepository.findByConfigKey(key)
				.filter(AppConfig::isActive);
		dbValue.ifPresent(cfg -> cache.put(cfg.getConfigKey(), cfg.getConfigValue()));
		return dbValue.map(AppConfig::getConfigValue);
	}

	/**
	 * Save or update a config
	 */
	@Transactional
	public AppConfig saveConfig(String key, String value, String type, String description) {
		AppConfig config = appConfigRepository.findByConfigKey(key).orElse(new AppConfig(key, value));
		config.setConfigValue(value);
		config.setConfigType(type);
		config.setDescription(description);
		config.setActive(true);
		Timestamp now = new Timestamp(System.currentTimeMillis());
		config.setUpdatedDate(now);
		if (config.getCreatedDate() == null) {
			config.setCreatedDate(now);
		}

		AppConfig saved = appConfigRepository.save(config);
		cache.put(saved.getConfigKey(), saved.getConfigValue());
		logger.info("Configuration saved: key={}, value={}", saved.getConfigKey(), saved.getConfigValue());
		return saved;
	}

	/**
	 * Disable a config
	 */
	@Transactional
	public void disableConfig(String key) {
		appConfigRepository.findByConfigKey(key).ifPresent(cfg -> {
			cfg.setActive(false);
			cfg.setUpdatedDate(new Timestamp(System.currentTimeMillis()));
			appConfigRepository.save(cfg);
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
		Timestamp startTime = new Timestamp(System.currentTimeMillis());

		// Find ALL changes, regardless of active status
		List<AppConfig> changes = appConfigRepository.findByUpdatedDateAfter(lastRefresh);

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
