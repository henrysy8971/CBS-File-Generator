package com.silverlakesymmetri.cbs.fileGenerator.constants;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class FileGenerationConstants {

	// Prevent instantiation
	private FileGenerationConstants() {
	}

	public static final String FILE_GEN_POLL_JOB = "fileGenPollJob";
	public static final String FILE_GEN_ADHOC_JOB = "fileGenAdHocJob";
	public static final String FILE_GEN_GROUP = "file-generation-group";
	public static final String FILE_GEN_TRIGGER_NAME = "fileGenPollTrigger";

	// Optional: add more file generation constants here
	public static final int HIGH_LOAD_THRESHOLD = 50;
	public static final String HTTP_HEADER_METADATA_KEY_USER_NAME = "X-User-Name";
	public static final String HTTP_HEADER_METADATA_USER_NAME_DEFAULT = "SYSTEM";
	public static final int INTERFACE_TYPE_LENGTH = 50;
	public static final int MIN_CHUNK_SIZE = 1;
	public static final int MAX_CHUNK_SIZE = 10000;
	public static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList("xml", "csv", "txt", "json", "dat"));

	/**
	 * Identifiers for File Generation Interfaces.
	 * <p>
	 * IMPORTANT: These constants must match the keys defined in 'interface-config.json' exactly.
	 * When adding a new interface to the JSON configuration, a corresponding constant
	 * should be defined here to ensure type-safe job routing and metadata look-ups
	 * across the Batch and Quartz layers.
	 */
	public static final String ORDER_INTERFACE = "ORDER_INTERFACE";
}
