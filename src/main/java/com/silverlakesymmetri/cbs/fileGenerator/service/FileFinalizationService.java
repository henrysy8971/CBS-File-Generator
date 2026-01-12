package com.silverlakesymmetri.cbs.fileGenerator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
	 * 1. Atomically move .part -> final file
	 * 2. Generate SHA256 checksum file (.sha)
	 */
	public boolean finalizeFile(String partFilePath) {
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

		// Strip .part (5 characters)
		Path finalPath = Paths.get(partPathStr.replaceAll("\\.part$", ""));

		try {
			// Generate SHA while it's still a .part to ensure integrity before rename
			String shaPath = generateShaFile(partPath);
			if (shaPath == null) return false;

			moveFileSafely(partPath, finalPath);

			// Rename the .sha file to match the new final filename
			Path oldSha = Paths.get(shaPath);
			Path newSha = Paths.get(finalPath + ".sha");
			Files.move(oldSha, newSha, StandardCopyOption.REPLACE_EXISTING);

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
		} catch (Exception e) {
			logger.warn("Atomic move failed for {}, using REPLACE_EXISTING", source.getFileName());
			Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

			// Safety check for the split logic
			if (!shaContent.contains("=")) {
				logger.error("Malformed SHA file: {}", shaPath);
				return false;
			}

			String expectedHash = shaContent.substring(shaContent.indexOf("=") + 1).trim();
			String actualHash = calculateSha256(filePathStr);
			return expectedHash.equalsIgnoreCase(actualHash);

		} catch (Exception e) {
			logger.error("Error verifying SHA file for: {}", filePathStr, e);
			return false;
		}
	}

	/**
	 * Helper to apply permissions only if the OS supports them.
	 */
	private void applyPosixPermissions(Path path) {
		try {
			if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
				Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-r--r--");
				Files.setPosixFilePermissions(path, perms);
				logger.debug("Applied POSIX permissions {} to {}", "rw-r--r--", path);
			}
		} catch (Exception e) {
			logger.warn("Failed to set POSIX permissions for {}: {}", path, e.getMessage());
		}
	}
}
