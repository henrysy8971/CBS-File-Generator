package com.silverlakesymmetri.cbs.fileGenerator.repository;

import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGenerationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FileGenerationAuditRepository extends JpaRepository<FileGenerationAudit, Long> {

	/**
	 * Find all audit records for a specific jobId, ordered by changedDate descending.
	 * Useful for fetching the latest status changes first.
	 *
	 * @param jobId Job ID to filter
	 * @return List of audit records
	 */
	List<FileGenerationAudit> findByJobIdOrderByChangedDateDesc(String jobId);

	/**
	 * Optional: Find the latest audit record for a specific jobId
	 *
	 * @param jobId Job ID
	 * @return Latest audit record or null
	 */
	FileGenerationAudit findFirstByJobIdOrderByChangedDateDesc(String jobId);
}
