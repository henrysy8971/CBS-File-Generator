package com.silverlakesymmetri.cbs.fileGenerator.repository;

import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileGenerationRepository extends JpaRepository<FileGeneration, Long> {
	Optional<FileGeneration> findByJobId(String jobId);

	List<FileGeneration> findByStatus(String status);

	List<FileGeneration> findByStatusAndCreatedBy(String status, String createdBy);

	boolean existsByInterfaceTypeAndStatus(String interfaceType, String status);
}
