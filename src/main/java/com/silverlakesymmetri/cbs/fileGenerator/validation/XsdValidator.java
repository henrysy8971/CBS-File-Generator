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
import java.util.Locale;
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

	@Value("${validation.xsd.strict-mode:false}")
	private boolean strictMode;

	/**
	 * Thread-safe cache for loaded schemas.
	 * Null values indicate schema load failure.
	 */
	private static final Map<String, Optional<Schema>> SCHEMA_CACHE = new ConcurrentHashMap<>();

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
			logStrictMode(e, "Invalid path or argument while opening XML file for validation: {}",
					xmlFile.getAbsolutePath());
			return !strictMode;
		} catch (UnsupportedOperationException e) {
			// Thrown if the file system does not support the operation
			logStrictMode(e, "Unsupported file system operation while opening XML file for validation: {}",
					xmlFile.getAbsolutePath());
			return !strictMode;
		} catch (IOException e) {
			// I/O issues: file locked, deleted mid-read, disk error, etc.
			logStrictMode(e, "I/O error while opening XML file for validation: {}",
					xmlFile.getAbsolutePath());
			return !strictMode;
		} catch (SecurityException e) {
			// SecurityManager or OS-level permission issues
			logStrictMode(e, "Security exception while opening XML file for validation: {}",
					xmlFile.getAbsolutePath());
			return !strictMode;
		}
	}

	/**
	 * Unified private method to handle the validation logic.
	 */
	private boolean executeValidation(StreamSource source, String schemaFileName) {
		try {
			Optional<Schema> schema = getOrLoadSchema(schemaFileName);
			if (!schema.isPresent()) {
				logger.warn("Schema not found: {}. StrictMode: {}", schemaFileName, strictMode);
				return !strictMode;
			}

			validateXml(source, schema.get(), schemaFileName);
			return true;
		} catch (SAXParseException e) {
			return !strictMode;
		} catch (Exception e) {
			logStrictMode(e, "Unexpected validation error for schema {}: {}", schemaFileName, e.getMessage());
			return !strictMode;
		}
	}

	/**
	 * Load schema from cache or classpath.
	 */
	private Optional<Schema> getOrLoadSchema(String schemaFileName) {
		schemaFileName = schemaFileName.trim().toLowerCase(Locale.ROOT);
		return SCHEMA_CACHE.computeIfAbsent(schemaFileName, file -> {
			// 1. Try File System First
			if (StringUtils.hasText(externalConfigDir)) {
				String dir = externalConfigDir.trim();
				Path baseDir = Paths.get(dir).toAbsolutePath().normalize();
				Path externalPath = baseDir.resolve("xsd").resolve(file).normalize();

				if (!externalPath.startsWith(baseDir)) {
					logger.error("Schema file path traversal attempt blocked: {}", file);
				} else if (Files.exists(externalPath, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(externalPath)) {
					try (InputStream is = Files.newInputStream(externalPath)) {
						return createSchemaFromStream(is, externalPath.toString());
					} catch (IOException e) {
						logStrictMode(e, "Failed to get external path input stream: {}", externalPath.toString());
						// Don't return empty yet, try classpath fallback
					}
				}
			}

			// 2. Fallback to Classpath
			ClassPathResource resource = new ClassPathResource("xsd/" + file);

			if (!resource.exists() || !resource.isReadable()) {
				logger.warn("XSD schema not found in External or Classpath locations: {}", file);
				return Optional.empty();
			}

			try (InputStream is = resource.getInputStream()) {
				return createSchemaFromStream(is, resource.getPath());
			} catch (IOException e) {
				logStrictMode(e, "Failed to get classpath resource input stream: {}", file);
				return Optional.empty();
			}
		});
	}

	/**
	 * Clear cached schemas (useful for tests or hot reload scenarios).
	 */
	public void clearSchemaCache() {
		SCHEMA_CACHE.clear();
		logger.info("XSD schema cache cleared");
	}

	/**
	 * Internal core validation logic using JAXP Source.
	 *
	 * @throws SAXParseException for any validation or IO failure
	 */
	private void validateXml(StreamSource source, Schema schema, String schemaFileName) throws SAXParseException {
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
			logStrictMode(e, "XSD Validation. Schema: {}, Error at Line: {}, Column: {}. Reason: {}",
					schemaFileName, e.getLineNumber(), e.getColumnNumber(), e.getMessage());
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
			factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			StreamSource source = new StreamSource(is);
			source.setSystemId(filePath);
			return Optional.of(factory.newSchema(source));
		} catch (SAXException | IllegalArgumentException e) {
			logStrictMode(e, "Failed to create Schema from stream: {}", filePath);
			return Optional.empty();
		}
	}

	private void logStrictMode(Exception e, String msg, Object... args) {
		if (strictMode) {
			logger.error(msg, append(args, e));
		} else {
			logger.warn(msg, append(args, e));
		}
	}

	private Object[] append(Object[] args, Exception e) {
		Object[] newArgs = new Object[args.length + 1];
		System.arraycopy(args, 0, newArgs, 0, args.length);
		newArgs[args.length] = e;
		return newArgs;
	}
}
