package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;

/**
 * Interface for pluggable output format writers.
 * Supports multiple formats: XML, CSV, Fixed-Length, Delimited, etc.
 */
public interface OutputFormatWriter extends ItemStreamWriter<DynamicRecord> {

	/**
	 * Initialize writer with output file path and configuration
	 */
	void init(String outputFilePath, String interfaceType) throws Exception;

	/**
	 * Close and finalize output file
	 */
	void close() throws ItemStreamException;

	/**
	 * Get total records written
	 */
	long getRecordCount();

	/**
	 * Get total records skipped
	 */
	long getSkippedCount();

	String getPartFilePath();
}
