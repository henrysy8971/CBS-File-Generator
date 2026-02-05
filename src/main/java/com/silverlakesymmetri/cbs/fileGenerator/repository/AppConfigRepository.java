package com.silverlakesymmetri.cbs.fileGenerator.repository;

import com.silverlakesymmetri.cbs.fileGenerator.entity.AppConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppConfigRepository extends JpaRepository<AppConfig, Long> {

	// Find specific key
	Optional<AppConfig> findByConfigKey(String configKey);

	// Initial load: all active configs
	List<AppConfig> findByActiveTrue();

	// Partial refresh: active configs updated after last refresh
	List<AppConfig> findByActiveTrueAndUpdatedDateAfter(LocalDateTime lastModified);

}
