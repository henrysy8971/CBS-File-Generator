package com.silverlakesymmetri.cbs.fileGenerator.service;

public enum FileGenerationStatus {
	PENDING,        // queued but not started
	PROCESSING,     // batch running
	STOPPED,        // batch stopped
	FINALIZING,     // renaming, checksum, post-processing
	COMPLETED,      // file fully ready
	FAILED;         // terminal failure

	/**
	 * Optional: Check if the status is terminal.
	 * Useful for preventing updates to jobs that are already finished.
	 */
	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED;
	}

	/**
	 * Optional: Check if a transition is valid.
	 */
	public boolean canTransitionTo(FileGenerationStatus nextStatus) {
		if (this.isTerminal()) return false;

		// Example logic: cannot go from PENDING straight to COMPLETED
		return this != PENDING || nextStatus != COMPLETED;
	}
}
