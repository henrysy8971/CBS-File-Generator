package com.silverlakesymmetri.cbs.fileGenerator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FileFinalizationService {
	private static final Logger logger = LoggerFactory.getLogger(FileFinalizationService.class);
	private static final String SHA256_ALGORITHM = "SHA-256";
	private static final int BUFFER_SIZE = 8192;
	private static final Pattern SHA_PATTERN = Pattern.compile("^SHA256\\((.+?)\\)=\\s*([a-fA-F0-9]{64})$");
	private static final boolean POSIX_SUPPORTED = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

	@Value("${file.generation.permissions:rw-r--r--}")
	private String filePermissions;

	/**
	 * Finalize a .part file safely:
	 * 1. Atomically move .part -> final file
	 * 2. Generate SHA256 checksum file (.sha)
	 */
	public boolean finalizeFile(String partFilePath) {
		Path partPath;
		try {
			partPath = normalizePartPath(partFilePath);
			if (partPath == null) return false;
		} catch (SecurityException e) {
			return false;
		}

		Path finalPath;
		try {
			finalPath = stripPartExtension(partPath);
		} catch (IllegalArgumentException e) {
			return false;
		}

		Path shaPartPath = null;
		boolean success = false;

		try {
			// 1. Generate SHA while still .part
			shaPartPath = generateShaFile(partPath);
			if (shaPartPath == null) return false;

			// 2. Move data file: .part -> final
			moveFileSafely(partPath, finalPath);

			// 3. Move SHA to match final filename
			Path finalShaPath = shaPartPath.resolveSibling(finalPath.getFileName().toString() + ".sha");
			moveFileSafely(shaPartPath, finalShaPath);

			// 4. Permissions
			if (Files.exists(finalPath)) {
				applyPosixPermissions(finalPath);
			}

			if (Files.exists(finalShaPath)) {
				applyPosixPermissions(finalShaPath);
			}

			success = true;
			return true;
		} catch (Exception e) {
			logger.error("Fatal error during finalization of {}", partFilePath, e);
			return false;
		} finally {
			if (!success && shaPartPath != null) {
				cleanupIfExists(shaPartPath);
			}
		}
	}

	/**
	 * Generate SHA256 checksum file safely (.sha.part -> .sha)
	 */
	private Path generateShaFile(Path partPath) {
		if (partPath == null) {
			logger.error("Cannot generate SHA file: filePath is null");
			return null;
		}

		Path finalPath = stripPartExtension(partPath);
		String finalFileName = finalPath.getFileName().toString();
		Path shaPartPath = partPath.resolveSibling(finalFileName + ".sha.part");

		try {
			String hash = calculateSha256(partPath);
			if (hash == null) {
				logger.warn("SHA calculation returned null for {}", partPath);
				return null;
			}

			// Write SHA to .sha.part using UTF-8
			try (BufferedWriter writer = Files.newBufferedWriter(shaPartPath, StandardCharsets.UTF_8)) {
				writer.write(String.format("SHA256(%s)= %s", finalFileName, hash));
			}

			return shaPartPath;
		} catch (Exception e) {
			logger.error("Error generating SHA file for: {}", partPath, e);
			return null;
		}
	}

	private void moveFileSafely(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			logger.info("Atomically moved file from {} to {}", source, target);
		} catch (AtomicMoveNotSupportedException e) {
			logger.warn("Atomic move not supported for {}: {}. Using REPLACE_EXISTING",
					source.getFileName(), e.getMessage());
			Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			logger.info("File moved using REPLACE_EXISTING from {} to {}", source, target);
		} catch (FileAlreadyExistsException e) {
			logger.error("Target file already exists: {}", target);
			throw e;
		} catch (DirectoryNotEmptyException e) {
			logger.error("Target directory is not empty: {}", target);
			throw e;
		} catch (IOException e) {
			logger.error("I/O error while moving file from {} to {}", source, target, e);
			throw e;
		} catch (SecurityException e) {
			logger.error("Security manager denied move from {} to {}", source, target, e);
			throw e;
		}
	}

	/**
	 * Calculate SHA256 hash of a file
	 */
	private String calculateSha256(Path filePath) {
		try (InputStream fis = Files.newInputStream(filePath)) {
			MessageDigest digest = MessageDigest.getInstance(SHA256_ALGORITHM);
			byte[] buffer = new byte[BUFFER_SIZE];
			int bytesRead;
			while ((bytesRead = fis.read(buffer)) != -1) {
				digest.update(buffer, 0, bytesRead);
			}
			return bytesToHex(digest.digest());
		} catch (IOException | NoSuchAlgorithmException e) {
			logger.error("Error calculating SHA256 for: {}", filePath, e);
			return null;
		}
	}

	public String calculateSha256(String filePathStr) {
		filePathStr = this.safeTrim(filePathStr);
		if (filePathStr.isEmpty()) {
			return null;
		}
		return calculateSha256(Paths.get(filePathStr).toAbsolutePath().normalize());
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

	/**
	 * Cleanup .part file safely
	 */
	public void cleanupPartFile(String partFilePath) {
		partFilePath = this.safeTrim(partFilePath);

		if (partFilePath.isEmpty()) {
			return;
		}

		Path partPath = Paths.get(partFilePath).toAbsolutePath().normalize();

		try {
			if (Files.exists(partPath)) {
				Files.delete(partPath);
				logger.info("Part file cleaned up: {}", partFilePath);
			}
		} catch (Exception e) {
			logger.error("Failed to cleanup part file: {}", partFilePath, e);
		}
	}

	/**
	 * Verify a file against its SHA256 .sha file
	 */
	public boolean verifyShaFile(String filePathStr) {
		filePathStr = this.safeTrim(filePathStr);
		if (filePathStr.isEmpty()) return false;

		try {
			Path filePath = Paths.get(filePathStr).toAbsolutePath().normalize();
			Path shaPath = filePath.resolveSibling(filePath.getFileName().toString() + ".sha");

			if (!Files.exists(shaPath)) {
				logger.warn("SHA file not found: {}", shaPath);
				return false;
			}

			String shaContent = new String(Files.readAllBytes(shaPath), StandardCharsets.UTF_8);
			Matcher matcher = SHA_PATTERN.matcher(shaContent.trim());

			if (!matcher.matches()) {
				logger.error("Invalid SHA file format: {}", shaPath);
				return false;
			}

			String expectedHash = matcher.group(2);
			String actualHash = calculateSha256(filePathStr);

			if (actualHash == null) {
				logger.warn("Failed to calculate SHA for {}", filePathStr);
				return false;
			}

			if (!expectedHash.equalsIgnoreCase(actualHash)) {
				logger.warn("SHA mismatch for {}: expected {}, actual {}", filePathStr, expectedHash, actualHash);
				return false;
			}

			return true;
		} catch (Exception e) {
			logger.error("Error verifying SHA file for: {}", filePathStr, e);
			return false;
		}
	}

	/**
	 * Helper to apply permissions only if the OS supports them.
	 */
	private void applyPosixPermissions(Path path) {
		if (path == null) {
			logger.warn("Cannot apply POSIX permissions: path is null");
			return;
		}

		String permsStr = this.safeTrim(filePermissions);

		if (permsStr.isEmpty()) {
			logger.warn("Cannot apply POSIX permissions: filePermissions is null or empty");
			return;
		}

		try {
			if (!POSIX_SUPPORTED) {
				logger.debug("POSIX file attributes not supported for path {}", path);
				return;
			}

			// Validate permission string format
			Set<PosixFilePermission> perms;
			try {
				perms = PosixFilePermissions.fromString(permsStr);
			} catch (IllegalArgumentException e) {
				logger.warn("Invalid POSIX permission string '{}', skipping for {}", permsStr, path);
				return;
			}

			// Apply permissions
			Files.setPosixFilePermissions(path, perms);
			logger.debug("Applied POSIX permissions {} to {}", permsStr, path);
		} catch (Exception e) {
			logger.warn("Failed to set POSIX permissions for {}: {}", path, e.getMessage());
		}
	}

	private String safeTrim(String s) {
		return s == null ? "" : s.trim();
	}

	private Path normalizePartPath(String path) throws SecurityException {
		String trimmed = this.safeTrim(path);
		if (trimmed.isEmpty()) {
			logger.error("[normalizePartPath] partFilePath is empty");
			return null;
		}

		Path p = Paths.get(trimmed).toAbsolutePath().normalize();
		if (!p.getFileName().toString().toLowerCase().endsWith(".part")) {
			logger.error("[normalizePartPath] Not a .part file: {}", p);
			return null;
		}

		return p;
	}

	private Path stripPartExtension(Path partPath) {
		if (partPath == null) {
			throw new IllegalArgumentException("[stripPartExtension] partPath must not be null");
		}

		Path fileNamePath = partPath.getFileName();
		if (fileNamePath == null) {
			throw new IllegalArgumentException("[stripPartExtension] Invalid path: " + partPath);
		}

		String fileName = fileNamePath.toString();

		if (!fileName.toLowerCase().endsWith(".part")) {
			throw new IllegalArgumentException("[stripPartExtension] Not a .part file: " + fileName);
		}

		String baseName = fileName.substring(0, fileName.length() - 5); // ".part"

		if (baseName.isEmpty()) {
			throw new IllegalArgumentException("[stripPartExtension] Invalid .part filename: " + fileName);
		}

		return partPath.resolveSibling(baseName);
	}

	private void cleanupIfExists(Path path) {
		try {
			if (Files.exists(path)) {
				Files.delete(path);
				logger.debug("Cleaned orphan file {}", path);
			}
		} catch (Exception e) {
			logger.warn("Failed to cleanup orphan file {}", path, e);
		}
	}
}
