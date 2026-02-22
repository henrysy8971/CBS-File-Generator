package com.silverlakesymmetri.cbs.fileGenerator.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.util.Assert;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.FILE_GEN_PART_FILE_PATH;
import static com.silverlakesymmetri.cbs.fileGenerator.constants.FileGenerationConstants.FILE_GEN_TOTAL_RECORD_COUNT;

public abstract class AbstractBaseOutputWriter<T> implements ItemStreamWriter<T>, StepExecutionListener {
	protected final Logger logger = LoggerFactory.getLogger(getClass());

	// Shared state keys
	protected abstract String getByteOffsetKey();

	protected abstract String getRecordCountKey();

	protected FileOutputStream fileOutputStream;
	protected ByteTrackingOutputStream byteTrackingStream;
	protected BufferedOutputStream bufferedOutputStream;

	protected String outputFilePath;
	protected String partFilePath;
	protected String interfaceType;
	protected long recordCount = 0;
	protected boolean stepSuccessful = false;

	@Override
	public abstract void write(List<? extends T> items) throws Exception;

	public void init(String outputFilePath, String interfaceType) throws Exception {
		Assert.hasText(outputFilePath, "outputFilePath must not be empty");
		Assert.hasText(interfaceType, "interfaceType must not be empty");

		this.outputFilePath = outputFilePath.trim();
		this.interfaceType = interfaceType.trim();
		this.partFilePath = outputFilePath.endsWith(".part")
				? outputFilePath
				: outputFilePath + ".part";

		ensureDirectoryExists(outputFilePath);
		onInit(); // Format-specific initialization
	}

	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		try {
			File file = new File(partFilePath);
			long lastByteOffset = 0;
			boolean isRestart = false;

			if (executionContext.containsKey(getByteOffsetKey())) {
				lastByteOffset = executionContext.getLong(getByteOffsetKey(), 0L);
				recordCount = executionContext.getLong(getRecordCountKey(), 0L);
				isRestart = true;
				logger.info("Restart detected at byte offset: {}", lastByteOffset);
			}

			fileOutputStream = new FileOutputStream(file, isRestart);
			FileChannel channel = fileOutputStream.getChannel();

			if (isRestart) {
				if (lastByteOffset < channel.size()) {
					channel.truncate(lastByteOffset);
				}
			} else {
				channel.truncate(0);
				recordCount = 0;
			}

			byteTrackingStream = new ByteTrackingOutputStream(fileOutputStream, channel.size());
			bufferedOutputStream = new BufferedOutputStream(byteTrackingStream);

			openStream(bufferedOutputStream, isRestart);
		} catch (Exception e) {
			closeQuietly();
			throw new ItemStreamException("Failed to open stream", e);
		}
	}

	@Override
	public void update(ExecutionContext executionContext) {
		try {
			flushInternal();
			if (bufferedOutputStream != null) bufferedOutputStream.flush();
			if (fileOutputStream != null) {
				fileOutputStream.getChannel().force(false);
			}
			if (byteTrackingStream != null) {
				long currentOffset = byteTrackingStream.getBytesWritten();
				executionContext.putLong(getByteOffsetKey(), currentOffset);
				executionContext.putLong(getRecordCountKey(), recordCount);
				logger.debug("Saved restart state: bytes={}, records={}", currentOffset, recordCount);
			}
		} catch (Exception e) {
			throw new ItemStreamException("Failed to update execution context", e);
		}
	}

	// Abstract methods for children to implement format-specific logic
	protected abstract void onInit() throws Exception;

	protected abstract void openStream(OutputStream os, boolean isRestart) throws Exception;

	protected abstract void flushInternal() throws Exception;

	protected abstract void writeHeader() throws Exception;

	protected abstract void writeFooter() throws Exception;

	// Common Utilities
	private void ensureDirectoryExists(String path) throws IOException {
		Path parent = Paths.get(path).toAbsolutePath().getParent();
		if (parent != null) Files.createDirectories(parent);
	}

	protected void closeQuietly() {
		try {
			if (bufferedOutputStream != null) bufferedOutputStream.close();
		} catch (Exception ignored) {
		}
		try {
			if (fileOutputStream != null) fileOutputStream.close();
		} catch (Exception ignored) {
		}
	}

	@Override
	public void beforeStep(StepExecution stepExecution) {
		stepSuccessful = false;
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		// Populate Job Context with metadata for the JobListener to rename/move the file
		ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
		jobContext.putString(FILE_GEN_PART_FILE_PATH, partFilePath);
		jobContext.putLong(FILE_GEN_TOTAL_RECORD_COUNT, recordCount);
		stepSuccessful = (stepExecution.getStatus() == BatchStatus.COMPLETED);
		return stepExecution.getExitStatus();
	}

	public long getRecordCount() {
		return recordCount;
	}

	public String getPartFilePath() {
		return partFilePath;
	}

	public long getSkippedCount() {
		return 0;
	}

	// Inner class for byte tracking remains shared
	protected static class ByteTrackingOutputStream extends OutputStream {
		private final OutputStream delegate;
		private long bytesWritten;

		public ByteTrackingOutputStream(OutputStream delegate, long initialOffset) {
			this.delegate = delegate;
			this.bytesWritten = initialOffset;
		}

		@Override
		public void write(int b) throws IOException {
			delegate.write(b);
			bytesWritten++;
		}

		@SuppressWarnings("NullableProblems")
		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			java.util.Objects.requireNonNull(b);
			delegate.write(b, off, len);
			bytesWritten += len;
		}

		public long getBytesWritten() {
			return bytesWritten;
		}

		@Override
		public void flush() throws IOException {
			delegate.flush();
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}
	}
}