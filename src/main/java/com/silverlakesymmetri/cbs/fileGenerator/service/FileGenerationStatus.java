package com.silverlakesymmetri.cbs.fileGenerator.service;

public enum FileGenerationStatus {
	PENDING,        // queued but not started
	PROCESSING,     // batch running
	STOPPED,        // batch stopped
	FINALIZING,     // renaming, checksum, post-processing
	COMPLETED,      // file fully ready
	FAILED          // terminal failure
}
