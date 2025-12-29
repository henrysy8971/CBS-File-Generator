package com.silverlakesymmetri.cbs.fileGenerator.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.InputStream;
import java.io.StringReader;
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
   * Validate XML content against the specified XSD schema.
   *
   * @param xmlContent   XML payload to validate
   * @param schemaFileName XSD filename located under classpath:xsd/
   * @return true if valid, or validation is skipped in lenient mode
   */
  public boolean validateRecord(String xmlContent, String schemaFileName) {
    if (schemaFileName == null || schemaFileName.trim().isEmpty()) {
      logger.debug("No schema specified - skipping XSD validation");
      return true;
    }

    try {
      Optional<Schema> schemaOpt = getOrLoadSchema(schemaFileName);

      if (schemaOpt.isPresent()) {
        logger.warn("Schema not found: {} (strictMode={})", schemaFileName, strictMode);
        return !strictMode;
      }

      validateXml(xmlContent, schemaOpt.get());
      logger.debug("XML successfully validated against schema: {}", schemaFileName);
      return true;

    } catch (SAXException e) {
      logger.error("XML validation failed against schema {}: {}", schemaFileName, e.getMessage());
      return !strictMode;

    } catch (Exception e) {
      logger.error("Unexpected error during XSD validation for schema {}", schemaFileName, e);
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
      SchemaFactory factory =
          SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

      // Secure processing
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
   * Perform stream-based XML validation.
   */
  private void validateXml(String xmlContent, Schema schema) throws Exception {
    Validator validator = schema.newValidator();

    // Secure validator configuration
    validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

    validator.validate(new StreamSource(new StringReader(xmlContent)));
  }

  /**
   * Clear cached schemas (useful for tests or hot reload scenarios).
   */
  public void clearCache() {
    schemaCache.clear();
    logger.info("XSD schema cache cleared");
  }

  /**
   * Check if a schema exists on the classpath.
   */
  public boolean schemaExists(String schemaFileName) {
    if (schemaFileName == null || schemaFileName.trim().isEmpty()) {
      return false;
    }
    return new ClassPathResource(XSD_LOCATION + schemaFileName).exists();
  }
}
