package com.silverlakesymmetri.cbs.fileGenerator.controller;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.FileGenerationRequest;
import com.silverlakesymmetri.cbs.fileGenerator.dto.FileGenerationResponse;
import com.silverlakesymmetri.cbs.fileGenerator.dto.PagedResponse;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.exception.*;
import com.silverlakesymmetri.cbs.fileGenerator.service.BatchJobLauncher;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.HTTP_HEADER_METADATA_KEY_USER_NAME;
import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.HTTP_HEADER_METADATA_USER_NAME_DEFAULT;

@RestController
@RequestMapping("/api/v1/file-generation")
public class FileGenerationController {
	private static final Logger logger = LoggerFactory.getLogger(FileGenerationController.class);

	private final FileGenerationService fileGenerationService;
	private final BatchJobLauncher batchJobLauncher;
	private final InterfaceConfigLoader interfaceConfigLoader;
	private volatile boolean outputDirValid;
	private volatile Path outputDirPath = null;
	private final AtomicBoolean initialized = new AtomicBoolean(false);

	@Value("${file.generation.output-directory}")
	private String outputDirectory;

	@Autowired
	public FileGenerationController(FileGenerationService fileGenerationService, BatchJobLauncher batchJobLauncher,
									InterfaceConfigLoader interfaceConfigLoader) {
		this.fileGenerationService = fileGenerationService;
		this.batchJobLauncher = batchJobLauncher;
		this.interfaceConfigLoader = interfaceConfigLoader;
	}

	// ==================== Startup Initialization ====================
	@EventListener(ContextRefreshedEvent.class)
	public void init() {
		if (!initialized.compareAndSet(false, true)) return;

		String dir = outputDirectory == null ? "" : outputDirectory.trim();
		if (dir.isEmpty()) {
			logger.error("Output directory not configured");
			outputDirValid = false;
		} else {
			outputDirPath = Paths.get(dir).toAbsolutePath().normalize();
			try {
				Files.createDirectories(outputDirPath);
				outputDirValid = Files.isWritable(outputDirPath);
			} catch (Exception e) {
				outputDirValid = false;
				logger.error("Failed to initialize output directory", e);
			}
		}
	}

	// ==================== Generate File ====================
	@PostMapping("/generate")
	public ResponseEntity<FileGenerationResponse> generateFile(
			@Valid
			@RequestBody FileGenerationRequest request,
			@RequestHeader(value = HTTP_HEADER_METADATA_KEY_USER_NAME, required = false) String userName) {

		if (!outputDirValid) {
			if (outputDirPath == null) {
				throw new ConfigurationException("Output directory is not set");
			} else {
				throw new ConfigurationException("Output directory is unavailable");
			}
		}

		String interfaceType = Optional.ofNullable(request.getInterfaceType()).orElse("").trim();

		// ===== Interface configuration validation =====
		InterfaceConfig interfaceConfig = interfaceConfigLoader.getConfig(interfaceType);
		if (interfaceConfig == null) {
			throw new NotFoundException("Interface type '" + interfaceType + "' not configured");
		}

		if (!interfaceConfig.isEnabled()) {
			throw new ConfigurationException("Interface '" + interfaceType + "' is disabled");
		}

		// ===== Check for running job =====
		if (fileGenerationService.hasRunningJob(interfaceType)) {
			throw new ConflictException("A job for this interface is already running.");
		}

		logger.info("File generation request received - Interface: {}, User: {}", interfaceType, userName);

		// ===== Generate collision-resistant filename =====
		String fileName = String.format("%s_%d_%s.%s",
				interfaceType,
				System.currentTimeMillis(),
				UUID.randomUUID(),
				interfaceConfig.getOutputFileExtension());

		// ===== Create file generation record =====
		FileGeneration fileGen = fileGenerationService.createFileGeneration(
				fileName,
				outputDirPath.toString(),
				Optional.ofNullable(userName).orElse(HTTP_HEADER_METADATA_USER_NAME_DEFAULT),
				interfaceType
		);

		logger.info("File generation job created - JobId: {}, Interface: {}",
				fileGen.getJobId(), interfaceType);

		// ===== Launch batch job asynchronously =====
		batchJobLauncher.launchFileGenerationJob(fileGen.getJobId(), interfaceType);

		logger.info("File generation job queued - JobId: {}, Interface: {}",
				fileGen.getJobId(), interfaceType);

		FileGenerationResponse response = buildFileGenerationResponse(fileGen, fileName, interfaceType,
				"File generation job queued successfully");

		return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
	}

	// ==================== File Status ====================
	@GetMapping("/getFileGenerationStatus/{jobId}")
	public ResponseEntity<FileGenerationResponse> getFileGenerationStatus(@PathVariable String jobId) {
		FileGeneration fileGen = fileGenerationService.getFileGeneration(jobId)
				.orElseThrow(() -> new NotFoundException("Job not found"));
		return ResponseEntity.ok(buildFileGenerationResponse(fileGen, fileGen.getFileName(),
				fileGen.getInterfaceType(), fileGen.getErrorMessage()));
	}

	@GetMapping("/getFileGenerationsByStatus")
	public ResponseEntity<PagedResponse<FileGenerationResponse>> getFileGenerationsByStatus(
			@RequestParam("status") String statusParam,
			@RequestParam(value = "page", defaultValue = "0") int page,
			@RequestParam(value = "size", defaultValue = "20") int size,
			@RequestParam(value = "order", defaultValue = "desc") String order) {

		// 1. Convert String to Enum
		FileGenerationStatus status = FileGenerationStatus.fromString(statusParam);

		// 2. Create PageRequest (Spring Boot 1.5.x syntax)
		Sort.Direction sortOrder = "desc".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
		PageRequest pageRequest = new PageRequest(page, size, sortOrder, "createdDate");

		// 3. Call Service
		Page<FileGeneration> resultsPage = fileGenerationService.getFilesByStatus(status, pageRequest);

		// 4. Map to the PagedResponse DTO
		return ResponseEntity.ok(mapFileGenerationDetailsToReponse(resultsPage));
	}

	// ==================== Available Interfaces ====================
	@GetMapping("/interfaces")
	public ResponseEntity<Map<String, Object>> getAvailableInterfaces() {
		Map<String, InterfaceConfig> enabledConfigs = interfaceConfigLoader.getEnabledConfigs();
		Map<String, Object> response = new HashMap<>();
		response.put("totalInterfaces", enabledConfigs.size());
		response.put("interfaces", enabledConfigs.keySet());
		return ResponseEntity.ok(response);
	}

	// ==================== Interface Configuration ====================
	@GetMapping("/getConfigInfo/{interfaceType}")
	public ResponseEntity<?> getInterfaceConfiguration(@PathVariable String interfaceType) {
		InterfaceConfig interfaceConfig = interfaceConfigLoader.getConfig(interfaceType);
		if (interfaceConfig == null) throw new NotFoundException("Interface configuration not found: " + interfaceType);
		InterfaceConfig response = mapInterfaceDetailsToResponse(interfaceConfig);
		return ResponseEntity.ok(response);
	}

	private InterfaceConfig mapInterfaceDetailsToResponse(InterfaceConfig source) {
		InterfaceConfig target = new InterfaceConfig();
		target.setName(source.getName());
		target.setBeanioMappingFile(source.getBeanioMappingFile());
		target.setHaveHeaders(source.isHaveHeaders());
		target.setStreamName(source.getStreamName());
		target.setXsdSchemaFile(source.getXsdSchemaFile());
		target.setChunkSize(source.getChunkSize());
		target.setOutputFormat(source.getOutputFormat());
		target.setOutputFileExtension(source.getOutputFileExtension());
		target.setRootElement(source.getRootElement());
		target.setNamespace(source.getNamespace());
		target.setKeysetColumn(source.getKeysetColumn());
		target.setEnabled(source.isEnabled());
		target.setDescription(source.getDescription());
		return target;
	}

	@GetMapping("/downloadFileByJobId/{jobId}")
	public ResponseEntity<Resource> downloadFileByJobId(@PathVariable String jobId) throws IOException {
		// 1. Directory safety check (Volatile check)
		if (!outputDirValid || outputDirPath == null) {
			throw new ConfigurationException("Output storage is unavailable");
		}

		// 2. DB Metadata check
		FileGeneration fileGen = fileGenerationService.getFileGeneration(jobId)
				.orElseThrow(() -> new NotFoundException("Job not found"));

		if (!FileGenerationStatus.COMPLETED.equals(fileGen.getStatus())) {
			throw new ForbiddenException("File is not ready or generation failed");
		}

		// 3. Security: Path Traversal Protection
		Path resolvedPath = outputDirPath.resolve(fileGen.getFileName()).normalize();
		if (!resolvedPath.startsWith(outputDirPath)) {
			logger.error("Security Alert: Unauthorized path traversal attempt for jobId: {}", jobId);
			throw new ForbiddenException("Invalid file path");
		}

		if (!Files.exists(resolvedPath)) {
			throw new GoneException("File has been archived or deleted from disk");
		}

		// 4. Resource Preparation
		Resource resource = new org.springframework.core.io.FileSystemResource(resolvedPath.toFile());

		// 5. MIME Detection
		String contentType = "application/octet-stream";
		try {
			contentType = Optional.ofNullable(Files.probeContentType(resolvedPath)).orElse(contentType);
		} catch (IOException e) {
			logger.warn("MIME detection failed for {}", fileGen.getFileName());
		}

		// 6. Build Response with Range Support
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileGen.getFileName() + "\"")
				// Signal to browsers/download managers that we support Range requests
				.header(HttpHeaders.ACCEPT_RANGES, "bytes")
				.contentType(MediaType.parseMediaType(contentType))
				// Note: We DO NOT set contentLength manually here.
				// Spring's ResourceHttpMessageConverter calculates it for the specific range requested.
				.contentLength(Files.size(resolvedPath))
				.body(resource);
	}

	// ==================== Helper Methods ====================
	private FileGenerationResponse buildFileGenerationResponse(
			FileGeneration fileGen,
			String fileName,
			String interfaceType,
			String message
	) {
		FileGenerationResponse response = new FileGenerationResponse();
		response.setJobId(fileGen.getJobId());
		response.setStatus(fileGen.getStatus().name());
		response.setFileName(fileName);
		response.setInterfaceType(interfaceType);
		response.setRecordCount(fileGen.getRecordCount());
		response.setSkippedRecordCount(fileGen.getSkippedRecordCount());
		response.setInvalidRecordCount(fileGen.getInvalidRecordCount());
		response.setMessage(message);
		return response;
	}

	private PagedResponse<FileGenerationResponse> mapFileGenerationDetailsToReponse(Page<FileGeneration> page) {
		// 1. Convert the Entities to DTOs using your existing build method
		List<FileGenerationResponse> dtoList = page.getContent().stream()
				.map(fileGen -> buildFileGenerationResponse(
						fileGen,
						fileGen.getFileName(),
						fileGen.getInterfaceType(),
						fileGen.getErrorMessage()))
				.collect(Collectors.toList());

		// 2. Wrap it in the PagedResponse object
		PagedResponse<FileGenerationResponse> response = new PagedResponse<>();
		response.setContent(dtoList);
		response.setPage(page.getNumber());
		response.setSize(page.getSize());
		response.setTotalElements(page.getTotalElements());
		response.setTotalPages(page.getTotalPages());
		response.setLast(page.isLast());

		return response;
	}
}
