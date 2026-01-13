package com.silverlakesymmetri.cbs.fileGenerator.service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum FileGenerationStatus {
	PENDING,
	PROCESSING,
	STOPPED,
	FINALIZING,
	COMPLETED,
	FAILED;

	private static final Map<FileGenerationStatus, Set<FileGenerationStatus>> TRANSITIONS =
			new EnumMap<>(FileGenerationStatus.class);

	static {
		TRANSITIONS.put(PENDING,
				EnumSet.of(PROCESSING, STOPPED, FAILED));

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
}
