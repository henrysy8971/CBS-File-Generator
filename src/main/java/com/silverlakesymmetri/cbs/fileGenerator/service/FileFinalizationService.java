package com.silverlakesymmetri.cbs.fileGenerator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Set;

@Service
public class FileFinalizationService {
	private static final Logger logger = LoggerFactory.getLogger(FileFinalizationService.class);
	private static final String SHA256_ALGORITHM = "SHA-256";
	private static final int BUFFER_SIZE = 8192;
	@Value("${file.generation.permissions:rw-r--r--}")
	private String filePermissions;

	/**
	 * Finalize a .part file safely:
	 * 1. Atomically move .part -> final file
	 * 2. Generate SHA256 checksum file (.sha)
	 */
	public boolean finalizeFile(String partFilePath) {
		partFilePath = this.safeTrim(partFilePath);

		if (partFilePath.isEmpty()) {
			logger.error("Finalization failed: partFilePath is empty");
			return false;
		}

		Path partPath = Paths.get(partFilePath);
		if (!Files.exists(partPath)) {
			logger.error("Finalization failed: Part file missing at {}", partFilePath);
			return false;
		}

		String partPathStr = partPath.toString();
		if (!partPathStr.endsWith(".part")) {
			logger.error("Finalization failed: File {} does not have .part extension", partPathStr);
			return false;
		}

		Path finalPath = partPath.resolveSibling(partPath.getFileName().toString().replaceFirst("\\.part$", ""));

		try {
			// Generate SHA while it's still a .part to ensure integrity before rename
			String shaPath = generateShaFile(partPath);
			if (shaPath == null) return false;

			moveFileSafely(partPath, finalPath);

			// Rename the .sha file to match the new final filename
			Path oldSha = Paths.get(shaPath);
			Path newSha = oldSha.resolveSibling(finalPath.getFileName().toString() + ".sha");
			Files.move(oldSha, newSha, StandardCopyOption.REPLACE_EXISTING);
			logger.info("SHA file renamed from {} to {}", oldSha, newSha);

			applyPosixPermissions(finalPath);
			return true;
		} catch (Exception e) {
			logger.error("Fatal error during finalization of {}", partFilePath, e);
			return false;
		}
	}

	/**
	 * Generate SHA256 checksum file safely (.sha.part -> .sha)
	 */
	private String generateShaFile(Path filePath) {
		if (filePath == null) {
			logger.error("Cannot generate SHA file: filePath is null");
			return null;
		}

		String fileName = filePath.getFileName().toString();
		Path shaPartPath = filePath.resolveSibling(fileName + ".sha.part");
		Path finalShaPath = filePath.resolveSibling(fileName + ".sha");

		try {
			String hash = calculateSha256(filePath);
			if (hash == null) {
				logger.warn("SHA calculation returned null for {}", filePath);
				return null;
			}

			// Write SHA to .sha.part using UTF-8
			try (BufferedWriter writer = Files.newBufferedWriter(shaPartPath, StandardCharsets.UTF_8)) {
				writer.write(String.format("SHA256(%s)= %s", filePath.getFileName(), hash));
			}

			moveFileSafely(shaPartPath, finalShaPath);
			applyPosixPermissions(finalShaPath);
			return finalShaPath.toString();

		} catch (Exception e) {
			logger.error("Error generating SHA file for: {}", filePath, e);
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
		try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
			MessageDigest digest = MessageDigest.getInstance(SHA256_ALGORITHM);
			byte[] buffer = new byte[BUFFER_SIZE];
			int bytesRead;
			while ((bytesRead = fis.read(buffer)) != -1) {
				digest.update(buffer, 0, bytesRead);
			}
			return bytesToHex(digest.digest());
		} catch (Exception e) {
			logger.error("Error calculating SHA256 for: {}", filePath, e);
			return null;
		}
	}

	public String calculateSha256(String filePathStr) {
		filePathStr = this.safeTrim(filePathStr);
		if (filePathStr.isEmpty()) {
			return null;
		}
		return calculateSha256(Paths.get(filePathStr));
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

		Path partPath = Paths.get(partFilePath);

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
			Path filePath = Paths.get(filePathStr);
			Path shaPath = filePath.resolveSibling(filePath.getFileName().toString() + ".sha");

			if (!Files.exists(shaPath)) {
				logger.warn("SHA file not found: {}", shaPath);
				return false;
			}

			String shaContent = new String(Files.readAllBytes(shaPath), StandardCharsets.UTF_8);

			// Safety check for the split logic
			if (!shaContent.contains("=")) {
				logger.error("Malformed SHA file: {}", shaPath);
				return false;
			}

			String[] parts = shaContent.split("=", 2);

			if (parts.length != 2) {
				logger.warn("Malformed SHA file content: '{}'", shaContent);
				return false;
			}

			String expectedHash = parts[1].trim();
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
			if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
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
}
