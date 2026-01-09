package com.silverlakesymmetri.cbs.fileGenerator.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
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
	private static final String XSD_LOCATION = "xsd/";

	@Value("${validation.xsd.strict-mode:false}")
	private boolean strictMode;

	/**
	 * Thread-safe cache for loaded schemas.
	 * Optional is used to avoid null caching.
	 */
	private final Map<String, Optional<Schema>> schemaCache = new ConcurrentHashMap<>();

	/**
	 * Validates a full file on disk.
	 * Use this in your Tasklet for post-generation validation.
	 */
	public boolean validateFullFile(File xmlFile, String schemaFileName) {
		if (schemaFileName == null || schemaFileName.trim().isEmpty()) {
			return true;
		}

		if (!xmlFile.exists()) {
			logger.error("Validation failed: File not found at {}", xmlFile.getAbsolutePath());
			return false;
		}

		try (InputStream is = Files.newInputStream(xmlFile.toPath())) {
			return executeValidation(new StreamSource(xmlFile), schemaFileName);
		} catch (Exception e) {
			logger.error("Could not open file for validation: {}", xmlFile.getPath(), e);
			return !strictMode;
		}
	}

	/**
	 * Unified private method to handle the validation logic.
	 */
	private boolean executeValidation(StreamSource source, String schemaFileName) {
		try {
			Optional<Schema> schemaOpt = getOrLoadSchema(schemaFileName);
			if (!schemaOpt.isPresent()) {
				logger.warn("Schema not found: {}. StrictMode: {}", schemaFileName, strictMode);
				return !strictMode;
			}

			validateXml(source, schemaOpt.get());
			return true;
		} catch (Exception e) {
			logger.error("XSD validation failed for schema {}: {}", schemaFileName, e.getMessage());
			return !strictMode;
		}
	}

	/**
	 * Load schema from cache or classpath.
	 */
	private Optional<Schema> getOrLoadSchema(String schemaFileName) {
		return schemaCache.computeIfAbsent(schemaFileName, this::loadSchema);
	}

	/**
	 * Load XSD schema safely from classpath.
	 */
	private Optional<Schema> loadSchema(String schemaFileName) {
		ClassPathResource resource = new ClassPathResource(XSD_LOCATION + schemaFileName);

		if (!resource.exists()) {
			logger.warn("XSD schema not found on classpath: {}{}", XSD_LOCATION, schemaFileName);
			return Optional.empty();
		}

		try (InputStream is = resource.getInputStream()) {
			SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			Schema schema = factory.newSchema(new StreamSource(is));
			logger.info("XSD schema loaded and cached: {}", schemaFileName);
			return Optional.of(schema);
		} catch (Exception e) {
			logger.error("Failed to load XSD schema: {}{}", XSD_LOCATION, schemaFileName, e);
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
	 */
	private void validateXml(StreamSource source, Schema schema) throws Exception {
		Validator validator = schema.newValidator();
		validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		try {
			validator.validate(source);
		} catch (SAXParseException e) {
			logger.error("XSD Validation Error at Line: {}, Column: {}. Reason: {}",
					e.getLineNumber(), e.getColumnNumber(), e.getMessage());
			throw e; // Re-throw so executeValidation can handle strictMode logic
		}
	}

}
