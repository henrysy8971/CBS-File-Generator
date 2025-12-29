package com.silverlakesymmetri.cbs.fileGenerator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Set;

@Service
public class FileFinalizationService {

	private static final Logger logger = LoggerFactory.getLogger(FileFinalizationService.class);
	private static final String SHA256_ALGORITHM = "SHA-256";
	private static final int BUFFER_SIZE = 8192;

	/**
	 * Finalize a .part file safely:
	 * 1. Atomically move .part → final file
	 * 2. Generate SHA256 checksum file (.sha)
	 */
	public boolean finalizeFile(String partFilePath) {
		Path partPath = Paths.get(partFilePath);
		if (!Files.exists(partPath)) {
			logger.error("Finalization failed: Part file missing at {}", partFilePath);
			return false;
		}

		// Safer path manipulation
		String partPathStr = partPath.toString();
		if (!partPathStr.endsWith(".part")) {
			logger.error("Finalization failed: File {} does not have .part extension", partPathStr);
			return false;
		}
		Path finalPath = Paths.get(partPathStr.substring(0, partPathStr.length() - 5));

		try {
			// Step 1: Atomic Move
			try {
				Files.move(partPath, finalPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception e) {
				logger.warn("Atomic move failed, falling back to REPLACE_EXISTING for {}", finalPath);
				Files.move(partPath, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}

			applyPosixPermissions(finalPath, "rw-r--r--");

			// Step 2: Generate SHA (Failure here shouldn't necessarily undo Step 1)
			try {
				generateShaFile(finalPath);
			} catch (Exception e) {
				logger.error("CRITICAL: File renamed but SHA generation failed for {}", finalPath, e);
				// In many banks, a missing SHA is a 'Failed' file.
				return false;
			}

			return true;
		} catch (Exception e) {
			logger.error("Fatal error during finalization of {}", partFilePath, e);
			return false;
		}
	}

	/**
	 * Generate SHA256 checksum file safely (.sha.part → .sha)
	 */
	private String generateShaFile(Path filePath) {
		Path shaPartPath = Paths.get(filePath.toString() + ".sha.part");
		Path finalShaPath = Paths.get(filePath + ".sha");

		try {
			String hash = calculateSha256(filePath);
			if (hash == null) return null;

			// Write SHA to .sha.part using UTF-8
			try (BufferedWriter writer = Files.newBufferedWriter(shaPartPath, StandardCharsets.UTF_8)) {
				writer.write(String.format("SHA256(%s)= %s", filePath.getFileName(), hash));
				writer.flush();
			}

			// Atomic rename to .sha
			try {
				Files.move(shaPartPath, finalShaPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception e) {
				logger.warn("Atomic move not supported for SHA file, using regular move: {}", e.getMessage());
				Files.move(shaPartPath, finalShaPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}

			applyPosixPermissions(finalShaPath, "rw-r--r--");
			return finalShaPath.toString();

		} catch (Exception e) {
			logger.error("Error generating SHA file for: {}", filePath, e);
			return null;
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
		try {
			Path shaPath = Paths.get(filePathStr + ".sha");

			if (!Files.exists(shaPath)) {
				logger.warn("SHA file not found: {}", shaPath);
				return false;
			}

			String shaContent = new String(Files.readAllBytes(shaPath), StandardCharsets.UTF_8);
			String expectedHash = shaContent.split("=")[1].trim();

			String actualHash = calculateSha256(filePathStr);
			if (expectedHash.equals(actualHash)) {
				logger.info("SHA256 verification successful for: {}", filePathStr);
				return true;
			} else {
				logger.warn("SHA256 verification failed for: {}", filePathStr);
				return false;
			}

		} catch (Exception e) {
			logger.error("Error verifying SHA file for: {}", filePathStr, e);
			return false;
		}
	}

	/**
	 * Helper to apply permissions only if the OS supports them.
	 */
	private void applyPosixPermissions(Path path, String permsStr) {
		try {
			if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
				Set<PosixFilePermission> perms = PosixFilePermissions.fromString(permsStr);
				Files.setPosixFilePermissions(path, perms);
				logger.debug("Applied POSIX permissions {} to {}", permsStr, path);
			}
		} catch (Exception e) {
			logger.warn("Failed to set POSIX permissions for {}: {}", path, e.getMessage());
		}
	}
}
