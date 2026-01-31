package com.silverlakesymmetri.cbs.fileGenerator.repository;

import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public interface FileGenerationRepository extends JpaRepository<FileGeneration, String> {
	// Standard lookup by unique Job ID
	Optional<FileGeneration> findByJobId(String jobId);

	// Updated to use Enum for type safety
	List<FileGeneration> findByStatus(FileGenerationStatus status);

	/**
	 * Paginated status lookup.
	 * Spring Data JPA handles the Pageable argument to generate LIMIT/OFFSET
	 * and automatically converts the Enum to its String value for the query.
	 */
	Page<FileGeneration> findAllByStatus(FileGenerationStatus status, Pageable pageable);

	List<FileGeneration> findByStatusAndCreatedBy(FileGenerationStatus status, String createdBy);

	// This utilizes the composite index (INTERFACE_TYPE, STATUS)
	boolean existsByInterfaceTypeAndStatus(String interfaceType, FileGenerationStatus status);

	/**
	 * Dashboard Query: Find recent jobs for a specific interface.
	 * Uses a custom JPQL query for better control.
	 */
	@Query("SELECT f FROM FileGeneration f WHERE f.interfaceType = :type ORDER BY f.createdDate DESC")
	List<FileGeneration> findRecentByInterface(@Param("type") String interfaceType);

	@Query("SELECT f.status FROM FileGeneration f WHERE f.jobId = :jobId")
	Optional<FileGenerationStatus> findStatusByJobId(@Param("jobId") String jobId);

	/**
	 * Update job status without loading the entity.
	 * Used by Batch job lifecycle transitions.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE FileGeneration f " +
			"SET f.status = :status, " +
			"f.errorMessage = :errorMessage, " +
			"f.completedDate = :completedDate " +
			"WHERE f.jobId = :jobId"
	)
	int updateStatus(
			@Param("jobId") String jobId,
			@Param("status") FileGenerationStatus status,
			@Param("errorMessage") String errorMessage,
			@Param("completedDate") Timestamp completedDate
	);

	/**
	 * ATOMIC STATUS TRANSITION
	 * Only updates the record if the current status matches the expectedStatus.
	 * This prevents race conditions in multi-threaded environments.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE FileGeneration f " +
			"SET f.status = :nextStatus, " +
			"f.errorMessage = :errorMessage, " +
			"f.completedDate = :completedDate " +
			"WHERE f.jobId = :jobId " +
			"AND f.status = :expectedStatus" // <--- THE ATOMIC CHECK
	)
	int updateStatusAtomic(
			@Param("jobId") String jobId,
			@Param("nextStatus") FileGenerationStatus nextStatus,
			@Param("expectedStatus") FileGenerationStatus expectedStatus,
			@Param("errorMessage") String errorMessage,
			@Param("completedDate") Timestamp completedDate
	);

	/**
	 * Update processing metrics in a single DB call.
	 * Should be called once per Step (not per item).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE FileGeneration f " +
			"SET f.recordCount=:processed, " +
			"f.skippedRecordCount =:skipped, " +
			"f.invalidRecordCount =:invalid " +
			"WHERE f.jobId =:jobId"
	)
	int updateMetrics(
			@Param("jobId") String jobId,
			@Param("processed") long processed,
			@Param("skipped") long skipped,
			@Param("invalid") long invalid
	);

	long countByStatus(FileGenerationStatus status);
}
