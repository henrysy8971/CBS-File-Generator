package com.silverlakesymmetri.cbs.fileGenerator.service;

import com.silverlakesymmetri.cbs.fileGenerator.model.FinalizationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FileFinalizationService {
	private static final Logger logger = LoggerFactory.getLogger(FileFinalizationService.class);
	private static final String SHA256_ALGORITHM = "SHA-256";
	private static final int BUFFER_SIZE = 8192;
	private static final Pattern SHA_PATTERN = Pattern.compile("^SHA256\\((.+?)\\)=\\s*([a-f0-9]{64})$", Pattern.CASE_INSENSITIVE);
	private static final boolean POSIX_SUPPORTED = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
	private static final String PART_EXTENSION = ".part";
	private static final String SHA_EXTENSION = ".sha";
	private static final String SHA_PART_EXTENSION = ".sha.part";

	@Value("${file.generation.permissions:rw-r--r--}")
	private String filePermissions;
	private Set<PosixFilePermission> posixPermissionsCache;

	// Thread-safe lock registry
	private final ConcurrentHashMap<Path, Object> fileLocks = new ConcurrentHashMap<>();

	/**
	 * Finalize a .part file safely:
	 * 1. Atomically move .part -> final file
	 * 2. Generate SHA256 checksum file (.sha)
	 */
	public FinalizationResult finalizeFile(String partFilePath) {
		PartFilePaths partFilePaths = normalizeAndResolvePart(partFilePath);
		if (partFilePaths == null) return FinalizationResult.INVALID_PART_FILE;

		Path canonicalPath;
		try {
			canonicalPath = partFilePaths.getPartPath().toAbsolutePath().normalize();
		} catch (SecurityException e) {
			logger.error("IO exception while getting real path {}", partFilePath, e);
			return FinalizationResult.SECURITY_ERROR;
		}

		Object lock = fileLocks.computeIfAbsent(canonicalPath, k -> new Object());

		synchronized (lock) {
			try {
				return doFinalize(partFilePaths);
			} finally {
				fileLocks.remove(canonicalPath, lock);
			}
		}
	}

	private FinalizationResult doFinalize(PartFilePaths paths) {
		Path partPath = paths.getPartPath();
		Path finalPath = paths.getFinalPath();
		Path finalShaPath = resolveShaPath(finalPath);
		Path shaPartPath = null;

		boolean partMoved = false;
		boolean shaMoved = false;
		FinalizationResult result = FinalizationResult.SUCCESS;

		try {
			shaPartPath = generateShaFile(paths).orElse(null);
			if (shaPartPath == null) return FinalizationResult.SHA_GENERATION_FAILED;

			moveFileSafely(partPath, finalPath);
			partMoved = true;

			moveFileSafely(shaPartPath, finalShaPath);
			shaMoved = true;

			for (Path p : Arrays.asList(finalPath, finalShaPath)) {
				if (!applyPosixPermissions(p)) {
					logger.warn("Permission application failed for {}", p);
				}
			}

			return result;

		} catch (IOException e) {
			logger.error("I/O error during finalization of {}", partPath, e);
			result = FinalizationResult.IO_ERROR;
			return result;

		} catch (SecurityException e) {
			logger.error("Security exception during finalization of {}", partPath, e);
			result = FinalizationResult.SECURITY_ERROR;
			return result;

		} finally {
			if (!partMoved && result.isRetryable()) cleanupIfExists(partPath);
			if (!shaMoved && result.isRetryable() && shaPartPath != null) cleanupIfExists(shaPartPath);
		}
	}

	/**
	 * Generate SHA256 checksum file safely (.sha.part -> .sha)
	 */
	private Optional<Path> generateShaFile(PartFilePaths paths) {
		Path partPath = paths.getPartPath();
		Path finalPath = paths.getFinalPath();
		String finalFileName = finalPath.getFileName().toString();
		Path shaPartPath = partPath.resolveSibling(finalFileName + SHA_PART_EXTENSION);

		try {
			Optional<String> hash = calculateSha256(partPath);
			if (!hash.isPresent()) return Optional.empty();

			try (BufferedWriter writer = Files.newBufferedWriter(
					shaPartPath,
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING
			)) {
				writer.write("SHA256(" + finalFileName + ")= " + hash.get());
			}
			return Optional.of(shaPartPath);
		} catch (IOException | SecurityException e) {
			logger.error("Error generating SHA file for: {}", partPath, e);
			return Optional.empty();
		}
	}

	private void moveFileSafely(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
			logger.info("Atomically moved file from {} to {}", source, target);
		} catch (AtomicMoveNotSupportedException e) {
			logger.debug("Atomic move not supported for {}. Using REPLACE_EXISTING", source);
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
			logger.info("File moved using REPLACE_EXISTING from {} to {}", source, target);
		} catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
			logger.error("Target file issue: {}", target, e);
			throw e;
		} catch (IOException | SecurityException e) {
			logger.error("I/O or security error moving {} to {}", source, target, e);
			throw e;
		}
	}

	private Optional<String> calculateSha256(Path filePath) {
		try (InputStream fis = Files.newInputStream(filePath);
			 DigestInputStream dis = new DigestInputStream(fis, MessageDigest.getInstance(SHA256_ALGORITHM))) {
			byte[] buffer = new byte[BUFFER_SIZE];
			while (dis.read(buffer) > 0) {}
			return Optional.of(bytesToHex(dis.getMessageDigest().digest()));
		} catch (IOException | NoSuchAlgorithmException e) {
			logger.error("Error calculating SHA256 for: {}", filePath, e);
			return Optional.empty();
		}
	}

	public Optional<String> calculateSha256(String filePathStr) {
		if (!StringUtils.hasText(filePathStr)) return Optional.empty();
		try {
			return calculateSha256(Paths.get(filePathStr).toAbsolutePath().normalize());
		} catch (InvalidPathException e) {
			logger.error("Invalid path for SHA calculation: {}", filePathStr, e);
			return Optional.empty();
		}
	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder hex = new StringBuilder();
		for (byte b : bytes) {
			String h = Integer.toHexString(0xff & b);
			if (h.length() == 1) hex.append('0');
			hex.append(h);
		}
		return hex.toString();
	}

	public void cleanupPartFile(String partFilePath) {
		PartFilePaths paths = normalizeAndResolvePart(partFilePath);
		if (paths == null) return;

		Path canonical;
		try {
			canonical = paths.getPartPath().toAbsolutePath().normalize();
		} catch (SecurityException | InvalidPathException e) {
			logger.debug("Cannot normalize path {}: {}", partFilePath, e.getMessage());
			return;
		}

		Object lock = fileLocks.computeIfAbsent(canonical, k -> new Object());
		synchronized (lock) {
			try {
				cleanupIfExists(paths.getPartPath());
				cleanupIfExists(paths.getPartPath().resolveSibling(paths.getPartPath().getFileName().toString() + SHA_PART_EXTENSION));
			} finally {
				fileLocks.remove(canonical, lock);
			}
		}
	}

	public boolean verifyShaFile(String filePathStr) {
		if (!StringUtils.hasText(filePathStr)) return false;

		try {
			Path filePath = Paths.get(filePathStr).toAbsolutePath().normalize();
			Path shaPath = resolveShaPath(filePath);

			if (!Files.exists(shaPath, LinkOption.NOFOLLOW_LINKS)) {
				logger.warn("SHA file not found: {}", shaPath);
				return false;
			}

			String shaContent;
			try (BufferedReader reader = Files.newBufferedReader(shaPath, StandardCharsets.UTF_8)) {
				shaContent = reader.readLine();
			}

			if (!StringUtils.hasText(shaContent)) {
				logger.error("SHA file is empty: {}", shaPath);
				return false;
			}

			Matcher matcher = SHA_PATTERN.matcher(shaContent.trim());
			if (!matcher.matches()) {
				logger.error("Invalid SHA file format: {}", shaPath);
				return false;
			}

			String expectedHash = matcher.group(2);
			Optional<String> actualHash = calculateSha256(filePathStr);

			if (!actualHash.isPresent()) {
				logger.warn("Failed to calculate SHA for {}", filePathStr);
				return false;
			}

			if (!expectedHash.equalsIgnoreCase(actualHash.get())) {
				logger.warn("SHA mismatch for {}: expected {}, actual {}", filePathStr, expectedHash, actualHash.get());
				return false;
			}

			return true;
		} catch (InvalidPathException | IOException | SecurityException e) {
			logger.error("Error verifying SHA file for: {}", filePathStr, e);
			return false;
		}
	}

	private boolean applyPosixPermissions(Path path) {
		if (path == null || posixPermissionsCache == null) return true;
		try {
			Files.setPosixFilePermissions(path, posixPermissionsCache);
			return true;
		} catch (IOException | SecurityException | UnsupportedOperationException e) {
			logger.warn("Failed to apply POSIX permissions to {}", path);
			return false;
		}
	}

	private PartFilePaths normalizeAndResolvePart(String path) {
		if (!StringUtils.hasText(path)) {
			logger.error("[normalizeAndResolvePart] partFilePath is empty");
			return null;
		}

		Path partPath;
		try {
			partPath = Paths.get(path.trim()).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			logger.error("Invalid path: {}", path, e);
			return null;
		}

		String fileName = partPath.getFileName().toString();
		if (!fileName.toLowerCase(Locale.ROOT).endsWith(PART_EXTENSION)) {
			logger.error("[normalizeAndResolvePart] Not a .part file: {}", partPath);
			return null;
		}
		if (fileName.length() <= PART_EXTENSION.length()) {
			logger.error("[normalizeAndResolvePart] Invalid .part filename: {}", fileName);
			return null;
		}

		return new PartFilePaths(partPath);
	}

	private void cleanupIfExists(Path path) {
		if (path == null) return;
		try {
			if (Files.deleteIfExists(path)) {
				logger.debug("Cleaned orphan file {}", path);
			}
		} catch (IOException | SecurityException e) {
			logger.warn("Failed to cleanup orphan file {}", path);
		}
	}

	private Path resolveShaPath(Path filePath) {
		String name = filePath.getFileName().toString();
		if (name.toLowerCase(Locale.ROOT).endsWith(SHA_EXTENSION)) return filePath;
		return filePath.resolveSibling(name + SHA_EXTENSION);
	}

	private static class PartFilePaths {
		private final Path partPath;
		private final Path finalPath;

		private PartFilePaths(Path partPath) {
			this.partPath = partPath;
			String fileName = partPath.getFileName().toString();
			String baseName = fileName.substring(0, fileName.length() - PART_EXTENSION.length());
			this.finalPath = partPath.resolveSibling(baseName);
		}

		Path getPartPath() {
			return partPath;
		}

		Path getFinalPath() {
			return finalPath;
		}
	}

	@PostConstruct
	private void initPosixPermissions() {
		if (POSIX_SUPPORTED) {
			try {
				posixPermissionsCache = PosixFilePermissions.fromString(filePermissions.trim());
			} catch (IllegalArgumentException e) {
				posixPermissionsCache = null;
				logger.warn("Invalid POSIX permission string: {}", filePermissions);
			}
		}
	}
}
