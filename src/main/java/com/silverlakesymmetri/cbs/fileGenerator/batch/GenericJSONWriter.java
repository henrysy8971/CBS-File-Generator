package com.silverlakesymmetri.cbs.fileGenerator.batch;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.silverlakesymmetri.cbs.fileGenerator.dto.DynamicRecord;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.List;

@Component
@StepScope
public class GenericJSONWriter extends AbstractBaseOutputWriter<DynamicRecord> implements OutputFormatWriter {

	private SequenceWriter sequenceWriter;
	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	protected String getByteOffsetKey() { return "json.writer.byteOffset"; }

	@Override
	protected String getRecordCountKey() { return "json.writer.recordCount"; }

	@Override
	protected void onInit() throws Exception {
		// Initialization if needed
	}

	@Override
	protected void openStream(OutputStream os, boolean isRestart) throws Exception {
		ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());

		// SequenceWriter allows writing elements one by one into a JSON Array []
		this.sequenceWriter = writer.writeValues(os);

		if (!isRestart) {
			writeHeader(); // Starts the array
		}
	}

	@Override
	public void write(List<? extends DynamicRecord> items) throws Exception {
		for (DynamicRecord record : items) {
			sequenceWriter.write(record.asMap());
			recordCount++;
		}
	}

	@Override
	protected void flushInternal() throws Exception {
		if (sequenceWriter != null) sequenceWriter.flush();
	}

	@Override protected void writeHeader() throws Exception {
		/* Handled by SequenceWriter */
	}

	@Override
	protected void writeFooter() throws Exception {
		// SequenceWriter handles closing the JSON array structure automatically on close
	}

	@Override
	public void close() {
		try {
			if (sequenceWriter != null) {
				sequenceWriter.close();
			}
		} catch (Exception e) {
			logger.error("Error closing JSON writer", e);
		} finally {
			super.closeQuietly();
		}
	}
}