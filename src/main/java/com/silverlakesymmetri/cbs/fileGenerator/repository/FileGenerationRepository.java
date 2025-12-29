package com.silverlakesymmetri.cbs.fileGenerator.repository;

import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileGenerationRepository extends JpaRepository<FileGeneration, Long> {

	// Standard lookup by unique Job ID
	Optional<FileGeneration> findByJobId(String jobId);

	// Updated to use Enum for type safety
	List<FileGeneration> findByStatus(FileGenerationStatus status);

	List<FileGeneration> findByStatusAndCreatedBy(FileGenerationStatus status, String createdBy);

	// This utilizes the composite index (INTERFACE_TYPE, STATUS)
	boolean existsByInterfaceTypeAndStatus(String interfaceType, FileGenerationStatus status);

	/**
	 * Dashboard Query: Find recent jobs for a specific interface.
	 * Uses a custom JPQL query for better control.
	 */
	@Query("SELECT f FROM FileGeneration f WHERE f.interfaceType = :type ORDER BY f.createdDate DESC")
	List<FileGeneration> findRecentByInterface(@Param("type") String interfaceType);
}
