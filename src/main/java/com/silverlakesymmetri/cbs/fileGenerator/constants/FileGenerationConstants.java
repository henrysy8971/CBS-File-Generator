package com.silverlakesymmetri.cbs.fileGenerator.constants;

public final class FileGenerationConstants {

	// Prevent instantiation
	private FileGenerationConstants() {}

	public static final String FILE_GEN_POLL_JOB = "fileGenPollJob";
	public static final String FILE_GEN_GROUP = "file-generation-group";
	public static final String FILE_GEN_TRIGGER_NAME = "fileGenPollTrigger";

	// Optional: add more file generation constants here
	public static final int HIGH_LOAD_THRESHOLD = 50;
	public static final String HTTP_HEADER_METADATA_KEY_USER_NAME = "X-User-Name";
	public static final String HTTP_HEADER_METADATA_USER_NAME_DEFAULT = "SYSTEM";
	public static final int INTERFACE_TYPE_LENGTH = 50;
	public enum HealthStatus {
		UP,
		DOWN,
		DEGRADED
	}
	public enum SystemLoad {
		NORMAL,
		HIGH,
		CRITICAL
	}
}
