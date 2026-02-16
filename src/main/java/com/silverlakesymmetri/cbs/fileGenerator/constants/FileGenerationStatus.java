package com.silverlakesymmetri.cbs.fileGenerator.constants;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum FileGenerationStatus {
	PENDING,
	QUEUED,
	PROCESSING,
	STOPPED,
	FINALIZING,
	COMPLETED,
	FAILED;

	private static final Map<FileGenerationStatus, Set<FileGenerationStatus>> TRANSITIONS =
			new EnumMap<>(FileGenerationStatus.class);

	static {
		TRANSITIONS.put(PENDING,
				EnumSet.of(QUEUED, PROCESSING, STOPPED, FAILED));

		TRANSITIONS.put(QUEUED,
				EnumSet.of(PROCESSING, FAILED));

		TRANSITIONS.put(PROCESSING,
				EnumSet.of(FINALIZING, STOPPED, FAILED));

		TRANSITIONS.put(FINALIZING,
				EnumSet.of(COMPLETED, FAILED));

		TRANSITIONS.put(STOPPED,
				EnumSet.of(PROCESSING, FAILED, PENDING));

		TRANSITIONS.put(COMPLETED,
				EnumSet.noneOf(FileGenerationStatus.class));

		TRANSITIONS.put(FAILED,
				EnumSet.noneOf(FileGenerationStatus.class));
	}

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED;
	}

	public boolean canTransitionTo(FileGenerationStatus nextStatus) {
		return TRANSITIONS
				.getOrDefault(this, EnumSet.noneOf(FileGenerationStatus.class))
				.contains(nextStatus);
	}

	public static FileGenerationStatus fromString(String status) {
		if (status == null || status.trim().isEmpty()) {
			throw new IllegalArgumentException("Status cannot be null or empty");
		}
		return FileGenerationStatus.valueOf(status.trim().toUpperCase());
	}
}
