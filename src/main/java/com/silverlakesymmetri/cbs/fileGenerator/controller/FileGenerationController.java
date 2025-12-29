package com.silverlakesymmetri.cbs.fileGenerator.controller;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.FileGenerationRequest;
import com.silverlakesymmetri.cbs.fileGenerator.dto.FileGenerationResponse;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.service.BatchJobLauncher;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/file-generation")
public class FileGenerationController {

	private static final Logger logger = LoggerFactory.getLogger(FileGenerationController.class);
	private static final Pattern INTERFACE_TYPE_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

	@Autowired
	private FileGenerationService fileGenerationService;

	@Autowired
	private BatchJobLauncher batchJobLauncher;

	@Autowired
	private InterfaceConfigLoader interfaceConfigLoader;

	@Value("${file.generation.output-directory}")
	private String outputDirectory;

	// ==================== Generate File ====================

	@PostMapping("/generate")
	public ResponseEntity<FileGenerationResponse> generateFile(
			@RequestBody FileGenerationRequest request,
			@RequestHeader(value = "X-User-Name", required = false) String userName) {

		String interfaceType = request.getInterfaceType();
		if (interfaceType == null || interfaceType.trim().isEmpty()) {
			return ResponseEntity.badRequest()
					.body(new FileGenerationResponse("VALIDATION_ERROR", "interfaceType is required"));
		}

		if (!INTERFACE_TYPE_PATTERN.matcher(interfaceType).matches()) {
			return ResponseEntity.badRequest()
					.body(new FileGenerationResponse(
							"VALIDATION_ERROR",
							"interfaceType must contain only alphanumeric characters or underscores"
					));
		}

		InterfaceConfig config;
		try {
			config = interfaceConfigLoader.getConfig(interfaceType);
		} catch (IllegalArgumentException ex) {
			logger.warn("Interface configuration not found: {}", interfaceType);
			return ResponseEntity.badRequest()
					.body(new FileGenerationResponse("VALIDATION_ERROR",
							"Interface type '" + interfaceType + "' not configured"));
		}

		if (!config.isEnabled()) {
			return ResponseEntity.badRequest()
					.body(new FileGenerationResponse(
							"VALIDATION_ERROR",
							"Interface '" + interfaceType + "' is disabled"
					));
		}

		if (fileGenerationService.hasRunningJob(interfaceType)) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
					.body(new FileGenerationResponse("CONFLICT", "A job for this interface is already running."));
		}

		logger.info("File generation request received - Interface: {}, User: {}", interfaceType, userName);

		// ===== Generate collision-resistant filename =====
		String fileName = String.format("%s_%d_%s.%s",
				interfaceType,
				System.currentTimeMillis(),
				UUID.randomUUID(),
				config.getOutputFileExtension());

		// ===== Create file generation record =====
		FileGeneration fileGen = fileGenerationService.createFileGeneration(
				fileName,
				outputDirectory,
				userName != null ? userName : "SYSTEM",
				interfaceType
		);

		// ===== Launch batch job asynchronously =====
		batchJobLauncher.launchFileGenerationJob(fileGen.getJobId(), interfaceType);

		FileGenerationResponse response = buildFileGenerationResponse(fileGen, fileName, interfaceType,
				"File generation job queued successfully");

		logger.info("File generation job queued - JobId: {}, Interface: {}",
				fileGen.getJobId(), interfaceType);

		return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
	}

	// ==================== File Status ====================

	@GetMapping("/status/{jobId}")
	public ResponseEntity<FileGenerationResponse> getFileGenerationStatus(@PathVariable String jobId) {
		Optional<FileGeneration> fileGenOpt = fileGenerationService.getFileGeneration(jobId);

		if (!fileGenOpt.isPresent()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new FileGenerationResponse("NOT_FOUND", "Job not found"));
		}

		FileGeneration fileGen = fileGenOpt.get();

		FileGenerationResponse response = buildFileGenerationResponse(fileGen, fileGen.getFileName(),
				fileGen.getInterfaceType(), fileGen.getErrorMessage());

		return ResponseEntity.ok(response);
	}

	// ==================== Pending Jobs ====================

	@GetMapping("/pending")
	public ResponseEntity<List<FileGenerationResponse>> getPendingFileGenerations() {
		List<FileGeneration> pendingFiles = fileGenerationService.getPendingFileGenerations();

		List<FileGenerationResponse> responseList = pendingFiles.stream()
				.map(fileGen -> buildFileGenerationResponse(fileGen, fileGen.getFileName(),
						fileGen.getInterfaceType(), fileGen.getErrorMessage()))
				.collect(Collectors.toList());

		return ResponseEntity.ok(responseList);
	}

	// ==================== Available Interfaces ====================

	@GetMapping("/interfaces")
	public ResponseEntity<Map<String, Object>> getAvailableInterfaces() {
		Map<String, InterfaceConfig> interfaces = interfaceConfigLoader.getEnabledConfigs();

		Map<String, Object> response = new HashMap<>();
		response.put("totalInterfaces", interfaces.size());
		response.put("interfaces", interfaces.keySet());

		return ResponseEntity.ok(response);
	}

	// ==================== Interface Configuration ====================

	@GetMapping("/interfaces/{interfaceType}")
	public ResponseEntity<?> getInterfaceConfiguration(@PathVariable String interfaceType) {
		try {
			InterfaceConfig config = interfaceConfigLoader.getConfig(interfaceType);
			config.setDataSourceQuery(null);
			return ResponseEntity.ok(config);
		} catch (IllegalArgumentException ex) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new FileGenerationResponse("NOT_FOUND",
							"Interface configuration not found: " + interfaceType));
		}
	}

	// ==================== Health Endpoint ====================

	@GetMapping("/health")
	public ResponseEntity<Map<String, Object>> health() {
		Map<String, Object> health = new HashMap<>();
		health.put("status", "UP");
		health.put("interfaces_loaded", interfaceConfigLoader.getAllConfigs().size());
		health.put("pending_jobs", fileGenerationService.getPendingFileGenerations().size());
		return ResponseEntity.ok(health);
	}

	// ==================== Helper Methods ====================

	private FileGenerationResponse buildFileGenerationResponse(FileGeneration fileGen,
															   String fileName,
															   String interfaceType,
															   String message) {
		FileGenerationResponse response = new FileGenerationResponse();
		response.setJobId(fileGen.getJobId());
		response.setStatus(fileGen.getStatus());
		response.setFileName(fileName);
		response.setInterfaceType(interfaceType);
		response.setRecordCount(fileGen.getRecordCount());
		response.setSkippedRecordCount(fileGen.getSkippedRecordCount());
		response.setInvalidRecordCount(fileGen.getInvalidRecordCount());
		response.setMessage(message);
		return response;
	}
}
