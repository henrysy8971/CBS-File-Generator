package com.silverlakesymmetri.cbs.fileGenerator.batch;

import java.io.IOException;
import java.io.OutputStream;

@SuppressWarnings("NullableProblems")
public class ByteTrackingOutputStream extends OutputStream {

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

	@Override
	public void write(byte[] b) throws IOException {
		delegate.write(b);
		bytesWritten += b.length;
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
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
