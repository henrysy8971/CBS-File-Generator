package com.silverlakesymmetri.cbs.fileGenerator.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XSD Schema validator for optional XML output validation.
 * Features:
 * - Safe for fat JAR execution
 * - XXE-safe XML processing
 * - Thread-safe schema caching
 * - Stream-based validation (no DOM)
 * - Strict / lenient validation modes
 */
@Component
public class XsdValidator {
	private static final Logger logger = LoggerFactory.getLogger(XsdValidator.class);
	@Value("${file.generation.external.config-dir:}")
	private String externalConfigDir;
	private static final String CLASSPATH_XSD_DIR = "xsd/";

	@Value("${validation.xsd.strict-mode:false}")
	private boolean strictMode;

	/**
	 * Thread-safe cache for loaded schemas.
	 * Null values indicate schema load failure.
	 */
	private final Map<String, Optional<Schema>> schemaCache = new ConcurrentHashMap<>();

	/**
	 * Validates a full file on disk.
	 * Use this in your Tasklet for post-generation validation.
	 */
	public boolean validateFullFile(File xmlFile, String schemaFileName) {
		if (xmlFile == null || !xmlFile.exists() || !xmlFile.isFile() || !xmlFile.canRead()) {
			logStrictMode(null, "Validation failed: File is invalid or unreadable: {}", xmlFile);
			return !strictMode;
		}

		if (!StringUtils.hasText(schemaFileName)) {
			logger.debug("No schema specified; skipping validation for file {}", xmlFile.getAbsolutePath());
			return !strictMode;
		}

		try (InputStream is = Files.newInputStream(xmlFile.toPath())) {
			StreamSource source = new StreamSource(is);
			source.setSystemId(xmlFile.getAbsolutePath());
			return executeValidation(source, schemaFileName.trim());
		} catch (IllegalArgumentException e) {
			// Thrown if the Path or URI is invalid
			logger.error("Invalid path or argument while opening XML file for validation: {}",
					xmlFile.getAbsolutePath(), e);
			return !strictMode;
		} catch (UnsupportedOperationException e) {
			// Thrown if the file system does not support the operation
			logger.error("Unsupported file system operation while opening XML file for validation: {}",
					xmlFile.getAbsolutePath(), e);
			return !strictMode;
		} catch (IOException e) {
			// I/O issues: file locked, deleted mid-read, disk error, etc.
			logger.error("I/O error while opening XML file for validation: {}",
					xmlFile.getAbsolutePath(), e);
			return !strictMode;
		} catch (SecurityException e) {
			// SecurityManager or OS-level permission issues
			logger.error("Security exception while opening XML file for validation: {}",
					xmlFile.getAbsolutePath(), e);
			return !strictMode;
		}
	}

	/**
	 * Unified private method to handle the validation logic.
	 */
	private boolean executeValidation(StreamSource source, String schemaFileName) {
		try {
			Schema schema = getOrLoadSchema(schemaFileName);
			if (schema == null) {
				logger.warn("Schema not found: {}. StrictMode: {}", schemaFileName, strictMode);
				return !strictMode;
			}

			validateXml(source, schema);
			return true;
		} catch (SAXParseException e) {
			logger.error("XSD Validation Error at Line: {}, Column: {}. Reason: {}",
					e.getLineNumber(), e.getColumnNumber(), e.getMessage(), e);
			return !strictMode;
		} catch (Exception e) {
			logger.error("Unexpected validation error for schema {}: {}", schemaFileName, e.getMessage(), e);
			return !strictMode;
		}
	}

	/**
	 * Load schema from cache or classpath.
	 */
	private Schema getOrLoadSchema(String schemaFileName) {
		return schemaCache.computeIfAbsent(schemaFileName, this::loadSchema)
				.orElse(null);
	}

	/**
	 * Load XSD schema safely from external directory or classpath.
	 */
	private Optional<Schema> loadSchema(String schemaFileName) {
		// 1. Try File System First
		if (StringUtils.hasText(externalConfigDir)) {
			String dir = externalConfigDir.trim();
			Path externalPath = Paths.get(dir, "xsd", schemaFileName).toAbsolutePath().normalize();

			if (!externalPath.startsWith(Paths.get(dir).toAbsolutePath().normalize())) {
				logger.error("Schema file path traversal attempt blocked: {}", schemaFileName);
				return Optional.empty();
			}

			if (Files.exists(externalPath, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(externalPath)) {
				try (InputStream is = Files.newInputStream(externalPath)) {
					return createSchemaFromStream(is, externalPath.toString());
				} catch (IOException e) {
					logStrictMode(e, "Failed to get external path input stream: {}", externalPath.toString());
					// Don't return empty yet, try classpath fallback
				}
			}
		}

		// 2. Fallback to Classpath
		String classpathPath = CLASSPATH_XSD_DIR + schemaFileName;
		ClassPathResource resource = new ClassPathResource(classpathPath);

		if (!resource.exists() || !resource.isReadable()) {
			logger.warn("XSD schema not found in External or Classpath locations: {}", schemaFileName);
			return Optional.empty();
		}

		try (InputStream is = resource.getInputStream()) {
			return createSchemaFromStream(is, classpathPath);
		} catch (IOException e) {
			logStrictMode(e, "Failed to get classpath resource input stream: {}", classpathPath);
			return Optional.empty();
		}
	}

	/**
	 * Clear cached schemas (useful for tests or hot reload scenarios).
	 */
	public void clearCache() {
		schemaCache.clear();
		logger.info("XSD schema cache cleared");
	}

	/**
	 * Internal core validation logic using JAXP Source.
	 *
	 * @throws SAXParseException for any validation or IO failure
	 */
	private void validateXml(StreamSource source, Schema schema) throws SAXParseException {
		// Validator is NOT thread-safe. Must be created per validation request.
		Validator validator = schema.newValidator();
		try {
			// XXE-safe properties for the validator instance
			try {
				validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
				validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
				validator.setProperty(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
			} catch (SAXNotRecognizedException | SAXNotSupportedException ignore) {
				// Not all JAXP implementations support these properties
				// Ignore if not supported
			}
			validator.validate(source);
		} catch (SAXParseException e) {
			logStrictMode(e, "XSD Validation Error at Line: {}, Column: {}. Reason: {}",
					e.getLineNumber(), e.getColumnNumber(), e.getMessage());
			throw e; // Re-throw so executeValidation can handle strictMode logic
		} catch (SAXException e) {
			logStrictMode(e, "XSD validation failed due to SAX error: {}", e.getMessage());
			throw new SAXParseException("XSD validation failed: " + e.getMessage(), null, e);
		} catch (IOException e) {
			logStrictMode(e, "I/O error while validating XML against XSD: {}", e.getMessage());
			throw new SAXParseException("I/O error during XML validation: " + e.getMessage(), null, e);
		}
	}

	private Optional<Schema> createSchemaFromStream(InputStream is, String filePath) {
		SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		try {
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			return Optional.of(factory.newSchema(new StreamSource(is)));
		} catch (SAXException | IllegalArgumentException e) {
			logger.error("Failed to create Schema from stream: {}", filePath, e);
			return Optional.empty();
		}
	}

	private void logStrictMode(Exception e, String msg, Object... args) {
		if (strictMode) logger.error(msg, args, e);
		else logger.warn(msg, args, e);
	}
}
