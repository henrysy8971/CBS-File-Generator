package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.config.InterfaceConfigLoader;
import com.silverlakesymmetri.cbs.fileGenerator.config.model.InterfaceConfig;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.beanio.BeanIOConfigurationException;
import org.beanio.BeanWriter;
import org.beanio.StreamFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@StepScope
public class GenericBeanIOWriter extends AbstractBaseOutputWriter<DynamicRecord> implements OutputFormatWriter {

	private static final Map<String, StreamFactory> FACTORY_CACHE = new ConcurrentHashMap<>();
	private final InterfaceConfigLoader interfaceConfigLoader;
	private BeanWriter beanWriter;
	private InterfaceConfig config;

	@Autowired
	public GenericBeanIOWriter(InterfaceConfigLoader interfaceConfigLoader) {
		this.interfaceConfigLoader = interfaceConfigLoader;
	}

	@Override
	protected String getByteOffsetKey() {
		return "beanio.writer.byteOffset";
	}

	@Override
	protected String getRecordCountKey() {
		return "beanio.writer.recordCount";
	}

	@Override
	protected void onInit() {
		this.config = interfaceConfigLoader.getConfig(interfaceType);
		if (this.config == null) {
			throw new IllegalArgumentException("No configuration found for interface type: " + interfaceType);
		}
	}

	@Override
	protected void openStream(OutputStream os, boolean isRestart) {
		StreamFactory factory = getOrCreateFactory(config.getBeanIoMappingFile());

		// Wrap the tracked/buffered stream with BeanIO's writer
		this.beanWriter = factory.createWriter(config.getStreamName(),
				new OutputStreamWriter(os, StandardCharsets.UTF_8));

		if (!isRestart) {
			writeHeader();
		}
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		if (beanWriter == null) {
			throw new IllegalStateException("BeanWriter is not initialized");
		}

		for (DynamicRecord record : items) {
			if (record != null) {
				try {
					beanWriter.write(record.asMap());
					recordCount++;
				} catch (Exception e) {
					logger.error("Failed writing record: {}", record, e);
					throw e;
				}
			}
		}
	}

	@Override
	protected void flushInternal() {
		// Flush cascade: Writer -> Buffer -> ByteTracker -> Disk
		if (beanWriter != null) {
			beanWriter.flush();
		}
	}

	@Override
	protected void writeHeader() {
		// Implement if the BeanIO mapping doesn't handle headers automatically
	}

	@Override
	protected void writeFooter() {
		// Implement if the BeanIO mapping doesn't handle footers automatically
	}

	@Override
	public void close() {
		try {
			if (beanWriter != null) {
				if (stepSuccessful) {
					writeFooter();
				}
				beanWriter.flush();
				beanWriter.close();
				beanWriter = null;
			}
		} catch (Exception e) {
			logger.warn("Failed to close BeanIO Writer correctly for {}", interfaceType, e);
		} finally {
			super.closeQuietly();
		}
	}

	// --- Stream Factory Management ---

	private StreamFactory getOrCreateFactory(String mappingFile) {
		return FACTORY_CACHE.computeIfAbsent(mappingFile, file -> {
			StreamFactory factory = StreamFactory.newInstance();

			// 1. Try External Path (relative to application execution/config folder)
			Path externalPath = resolveExternalMappingPath(file);
			if (externalPath != null && Files.exists(externalPath)) {
				try (InputStream is = new BufferedInputStream(Files.newInputStream(externalPath))) {
					factory.load(is);
					logger.info("Loaded BeanIO mapping from external path: {}", externalPath);
					return factory;
				} catch (IOException | BeanIOConfigurationException e) {
					logger.warn("Failed to load external mapping: {}. Falling back to classpath.", file);
				}
			}

			// 2. Fallback to Classpath
			try {
				ClassPathResource resource = new ClassPathResource("beanio/" + file);

				if (!resource.exists() || !resource.isReadable()) {
					throw new IllegalArgumentException("BeanIO mapping file not found / not readable: " + resource.getPath());
				}

				try (InputStream is = new BufferedInputStream(resource.getInputStream())) {
					factory.load(is);
					logger.info("Loaded BeanIO mapping from classpath: {}", resource.getPath());
					return factory;
				}
			} catch (IOException e) {
				throw new RuntimeException("CRITICAL: BeanIO mapping file not found or unreadable: " + file, e);
			} catch (BeanIOConfigurationException e) {
				throw new RuntimeException("CRITICAL: Syntax error in BeanIO XML mapping: " + file, e);
			}
		});
	}

	private Path resolveExternalMappingPath(String file) {
		if (!StringUtils.hasText(outputFilePath)) return null;

		Path baseDir = Paths.get(outputFilePath).toAbsolutePath().getParent();
		if (baseDir == null) return null;

		return baseDir.resolve("beanio").resolve(file).normalize();
	}

	public static void clearFactoryCache() {
		FACTORY_CACHE.clear();
	}
}