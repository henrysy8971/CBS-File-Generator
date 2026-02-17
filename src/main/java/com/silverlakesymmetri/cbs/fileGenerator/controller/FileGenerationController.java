package com.silverlakesymmetri.cbs.fileGenerator.controller;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.FileGenerationRequest;
import com.silverlakesymmetri.cbs.fileGenerator.dto.FileGenerationResponse;
import com.silverlakesymmetri.cbs.fileGenerator.dto.PagedResponse;
import com.silverlakesymmetri.cbs.fileGenerator.entity.FileGeneration;
import com.silverlakesymmetri.cbs.fileGenerator.exception.*;
import com.silverlakesymmetri.cbs.fileGenerator.service.BatchJobLauncherService;
import com.silverlakesymmetri.cbs.fileGenerator.service.FileGenerationService;
import com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationStatus;
import com.silverlakesymmetri.cbs.fileGenerator.service.RateLimiterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.*;

@RestController
@RequestMapping("/api/v1/file-generation")
public class FileGenerationController {
	private static final Logger logger = LoggerFactory.getLogger(FileGenerationController.class);

	private final FileGenerationService fileGenerationService;
	private final BatchJobLauncherService batchJobLauncherService;
	private final InterfaceConfigLoader interfaceConfigLoader;
	private final RateLimiterService rateLimiterService;
	private final AtomicBoolean outputDirValid = new AtomicBoolean(false);
	private volatile Path outputDirPath = null;
	private final AtomicBoolean initialized = new AtomicBoolean(false);

	@Value("${file.generation.output-directory}")
	private String outputDirectory;

	@Autowired
	public FileGenerationController(
			FileGenerationService fileGenerationService,
			BatchJobLauncherService batchJobLauncherService,
			InterfaceConfigLoader interfaceConfigLoader,
			RateLimiterService rateLimiterService) {
		this.fileGenerationService = fileGenerationService;
		this.batchJobLauncherService = batchJobLauncherService;
		this.interfaceConfigLoader = interfaceConfigLoader;
		this.rateLimiterService = rateLimiterService;
	}

	// ==================== Startup Initialization ====================
	@EventListener(ContextRefreshedEvent.class)
	public void init() {
		if (!initialized.compareAndSet(false, true)) return;

		String dir = outputDirectory == null ? "" : outputDirectory.trim();
		if (dir.isEmpty()) {
			logger.error("Output directory not configured");
			outputDirValid.set(false);
		} else {
			outputDirPath = Paths.get(dir).toAbsolutePath().normalize();
			try {
				Files.createDirectories(outputDirPath);
				outputDirValid.set(Files.isWritable(outputDirPath));
			} catch (Exception e) {
				outputDirValid.set(false);
				logger.error("Failed to initialize output directory", e);
			}
		}
	}

	// ==================== Generate File ====================
	@PostMapping("/generate")
	public ResponseEntity<FileGenerationResponse> generateFile(
			@Valid
			@RequestBody FileGenerationRequest request,
			@RequestHeader(value = HTTP_HEADER_METADATA_KEY_USER_NAME, required = false) String userName
	) {

		if (!outputDirValid.get()) {
			if (outputDirPath == null) {
				throw new ConfigurationException("Output directory is not set in application properties");
			} else {
				throw new ConfigurationException("Output directory is unavailable");
			}
		}

		String interfaceType = Optional.ofNullable(request.getInterfaceType()).orElse("").trim();

		// Extract Idempotency Key from Request (Preferred)
		String idempotencyKey = Optional.ofNullable(request.getIdempotencyKey()).orElse("").trim();

		// If client didn't send one, generate a random one (Fallback),
		// effectively making this specific request non-idempotent regarding retries.
		if (idempotencyKey.trim().isEmpty()) {
			idempotencyKey = UUID.randomUUID().toString();
		}

		// Rate Limit Check
		if (!rateLimiterService.tryConsume(interfaceType)) {
			logger.warn("Rate limit exceeded for interface: {}", interfaceType);
			throw new ForbiddenException("Rate limit exceeded. Please try again in a few seconds.");
		}

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
		String ext = interfaceConfig.getOutputFileExtension() != null ? interfaceConfig.getOutputFileExtension() : "txt";
		String fileName = interfaceType + "_" + UUID.randomUUID() + "." + ext;

		// ===== Create file generation record =====
		FileGeneration fileGen = fileGenerationService.createFileGeneration(
				fileName,
				outputDirPath.toString(),
				Optional.ofNullable(userName).orElse(HTTP_HEADER_METADATA_USER_NAME_DEFAULT),
				interfaceType,
				idempotencyKey
		);

		logger.info("File generation job created - JobId: {}, Interface: {}",
				fileGen.getJobId(), interfaceType);

		String requestId = MDC.get("requestId");
		if (requestId == null) {
			requestId = UUID.randomUUID().toString();  // Generate fallback
		}

		// ===== Launch batch job asynchronously =====
		batchJobLauncherService.launchFileGenerationJob(fileGen.getJobId(), interfaceType, requestId);

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
			@RequestParam(value = "size", defaultValue = "10") int size,
			@RequestParam(value = "order", defaultValue = "desc") String order) {

		// 1. Convert String to Enum
		FileGenerationStatus status = FileGenerationStatus.fromString(statusParam);

		// 2. Create PageRequest (Spring Boot 1.5.x syntax)
		Sort.Direction sortOrder = "desc".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
		PageRequest pageRequest = new PageRequest(page, size, sortOrder, "createdDate");

		// 3. Call Service
		Page<FileGeneration> resultsPage = fileGenerationService.getFilesByStatus(status, pageRequest);

		// 4. Map to the PagedResponse DTO
		return ResponseEntity.ok(mapFileGenerationDetailsToResponse(resultsPage));
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
		target.setBeanIoMappingFile(source.getBeanIoMappingFile());
		target.setHaveHeaders(source.isHaveHeaders());
		target.setStreamName(source.getStreamName());
		target.setXsdSchemaFile(source.getXsdSchemaFile());
		target.setOutputFormat(source.getOutputFormat());
		target.setOutputFileExtension(source.getOutputFileExtension());
		target.setRootElement(source.getRootElement());
		target.setNamespace(source.getNamespace());
		target.setKeySetColumn(source.getKeySetColumn());
		target.setEnabled(source.isEnabled());
		target.setDescription(source.getDescription());

		return target;
	}

	@GetMapping("/downloadFileByJobId/{jobId}")
	public ResponseEntity<Resource> downloadFileByJobId(
			@PathVariable String jobId,
			@RequestHeader(value = HTTP_HEADER_METADATA_KEY_USER_NAME, required = false) String userName) {
		// 1. Directory safety check (Volatile check)
		if (!outputDirValid.get() || outputDirPath == null) {
			throw new ConfigurationException("Output storage is unavailable");
		}

		// 2. DB Metadata check
		FileGeneration fileGen = fileGenerationService.getFileGeneration(jobId)
				.orElseThrow(() -> new NotFoundException("Job not found"));

		// Get the username of the user who created this file/job
		String createdBy = fileGen.getCreatedBy();

		// Skip permission check if the job was created by a system/default user
		if (!Objects.equals(createdBy, HTTP_HEADER_METADATA_USER_NAME_DEFAULT)
				&& !Objects.equals(createdBy, QUARTZ_BATCH_JOB_USER_NAME_DEFAULT)
				&& !Objects.equals(createdBy, userName)
		) {
			throw new ForbiddenException("You don't have permission to download this file");
		}

		if (!FileGenerationStatus.COMPLETED.equals(fileGen.getStatus())) {
			throw new ForbiddenException("File is not ready or generation failed");
		}

		// Validate incoming filename BEFORE using it
		String fileName = sanitizeFileName(fileGen.getFileName());
		if (fileName.contains("..") || fileName.contains("\\") || fileName.contains("/")) {
			logger.error("Security Alert: Malicious filename in database jobId={}: {}", jobId, fileGen.getFileName());
			throw new ForbiddenException("Invalid file path");
		}

		// 3. Security: Path Traversal Protection
		Path basePath = outputDirPath.toAbsolutePath().normalize();
		Path resolvedPath = outputDirPath.resolve(fileName).normalize();
		if (!resolvedPath.startsWith(basePath)) {
			logger.error("Security Alert: Unauthorized path traversal attempt for jobId: {}", jobId);
			throw new ForbiddenException("Invalid file path");
		}

		if (!Files.exists(resolvedPath, LinkOption.NOFOLLOW_LINKS)) {
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

		// Encode for RFC 5987
		String encodedFileName = encodeRFC5987(fileName);

		String contentDisposition =
				"attachment; filename=\"" + fileName + "\"; " +
						"filename*=UTF-8''" + encodedFileName;

		// 6. Build Response with Range Support
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
				// Signal to browsers/download managers that we support Range requests
				.header(HttpHeaders.ACCEPT_RANGES, "bytes")
				.contentType(MediaType.parseMediaType(contentType))
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

	private PagedResponse<FileGenerationResponse> mapFileGenerationDetailsToResponse(Page<FileGeneration> page) {
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

	private String encodeRFC5987(String value) {
		try {
			return URLEncoder.encode(value, "UTF-8")
					.replace("+", "%20");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException("UTF-8 not supported", e);
		}
	}

	private String sanitizeFileName(String name) {
		return name == null ? "" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
	}
}
